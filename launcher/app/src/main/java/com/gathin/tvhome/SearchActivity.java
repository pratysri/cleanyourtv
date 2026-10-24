package com.gathin.tvhome;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Search (hisense-home fork): one box for installed apps and for movies and shows, with the apps on this TV that
 * stream each title. Titles and availability come from TMDB, whose streaming data is JustWatch's; the read token is
 * baked in at build time from a private file (my-tv/tmdb.properties). Picking a title opens the app that has it, at
 * its own search when the app accepts one.
 */
public final class SearchActivity extends Activity {
    /** TMDB watch-provider id to the TV package that plays it (US). Only installed packages are shown. */
    private static final Map<Integer, String> PROVIDERS = new HashMap<>();

    static {
        PROVIDERS.put(8, "com.netflix.ninja");                    // Netflix
        PROVIDERS.put(1796, "com.netflix.ninja");                 // Netflix basic with ads
        PROVIDERS.put(9, "com.amazon.amazonvideo.livingroom");    // Amazon Prime Video
        PROVIDERS.put(119, "com.amazon.amazonvideo.livingroom");  // Amazon Prime Video
        PROVIDERS.put(2100, "com.amazon.amazonvideo.livingroom"); // Prime Video with ads
        PROVIDERS.put(337, "com.disney.disneyplus");              // Disney+
        PROVIDERS.put(15, "com.hulu.livingroomplus");             // Hulu
        PROVIDERS.put(1899, "com.wbd.stream");                    // Max
        PROVIDERS.put(384, "com.wbd.stream");                     // HBO Max (older id)
        PROVIDERS.put(386, "com.peacocktv.peacockandroid");       // Peacock Premium
        PROVIDERS.put(387, "com.peacocktv.peacockandroid");       // Peacock Premium Plus
        PROVIDERS.put(350, "com.apple.atve.androidtv.appletv");   // Apple TV+
        PROVIDERS.put(2, "com.apple.atve.androidtv.appletv");     // Apple TV (store)
        PROVIDERS.put(73, "com.tubitv");                          // Tubi
        PROVIDERS.put(300, "tv.pluto.android");                   // Pluto TV
        PROVIDERS.put(192, "com.google.android.youtube.tv");      // YouTube
        PROVIDERS.put(188, "com.google.android.youtube.tv");      // YouTube Premium
        PROVIDERS.put(122, "in.startv.hotstar");                  // Hotstar
        PROVIDERS.put(232, "com.graymatrix.did");                 // ZEE5
        PROVIDERS.put(237, "com.sonyliv");                        // SonyLIV
    }

    /** Every streaming app Search knows, for Home settings' "Services you pay for". */
    static List<String> servicePackages() {
        return new ArrayList<>(new java.util.LinkedHashSet<>(PROVIDERS.values()));
    }

    /** Apps whose own search a title can be handed to as a link, when ACTION_SEARCH is not taken. */
    private static final Map<String, String> SEARCH_LINKS = new HashMap<>();

    static {
        SEARCH_LINKS.put("com.netflix.ninja", "https://www.netflix.com/search?q=");
        SEARCH_LINKS.put("com.google.android.youtube.tv", "https://www.youtube.com/results?search_query=");
    }

    private static final LruCache<String, Bitmap> POSTERS = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount(); }
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newFixedThreadPool(3);
    private final AtomicInteger generation = new AtomicInteger();
    private float density;
    private Typeface regular, medium, light;
    /** What has been typed or said; the screen's own keyboard edits it, so no system keyboard is ever needed. */
    private final StringBuilder typed = new StringBuilder();
    private TextView query;
    private View caret;
    private LinearLayout keyboard, results;
    private ScrollView resultsScroll;
    private String token;
    private final Runnable search = this::runSearch;
    /** Opened with this extra (the remote's search key) it starts listening at once. */
    static final String EXTRA_VOICE = "voice";
    private static final int VOICE_REQUEST = 7;
    private static final String RECENT = "search_recent";
    private View mic;
    /** After a voice search, focus moves to the first result as soon as there is one. */
    private boolean focusFirstResult;
    /** The recogniser's other guesses: offered as "Also heard", and tried in turn when the first finds nothing. */
    private final List<String> heardAlso = new ArrayList<>();
    private final List<String> fallbacks = new ArrayList<>();

    // Listening: Google TV's own recogniser first (the one the remote's mic is tuned for), then the system's, then
    // the plain speech screen. Each is dropped for the next when it fails outright.
    private static final ComponentName KATNISS = new ComponentName("com.google.android.katniss",
            "com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService");
    private int engine;
    private SpeechRecognizer recognizer;
    private boolean listeningNow;
    private FrameLayout listening;
    private TextView listenStatus, listenHeard;
    private View listenRing;

    private static final class Title {
        String name, year, kind, poster;
        int id;
        final List<String> apps = new ArrayList<>();
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        regular = font(R.font.outfit_regular);
        medium = font(R.font.outfit_medium);
        light = font(R.font.outfit_light);
        token = getString(R.string.tmdb_read_token).trim();

        // Home's wallpaper, frosted, behind everything: Search reads as a layer over Home, not a separate app.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0D12);
        Bitmap frost = Frost.image();
        if (frost != null) {
            ImageView bg = new ImageView(this);
            bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            bg.setImageBitmap(frost);
            root.addView(bg, matchParent());
        }
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                frost != null ? new int[]{0x99070910, 0xC7070910, 0xE6070910}
                        : new int[]{0xFF141824, 0xFF0B0D12, 0xFF07080B}));
        root.addView(scrim, matchParent());

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(48), dp(34), dp(48), 0);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        root.addView(page, matchParent());

        // The mic, then the field.
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setClipChildren(false);
        mic = micButton();
        top.addView(mic, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(0, dp(56), 1f);
        fieldLp.leftMargin = dp(16);
        top.addView(field(), fieldLp);
        page.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // The keyboard on the left, results on the right.
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setClipChildren(false);
        body.setClipToPadding(false);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = dp(20);
        page.addView(body, bodyLp);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setClipChildren(false);
        keyboard = keyboard();
        left.addView(keyboard);
        TextView credit = text(token.isEmpty()
                ? "Movies and shows need a TMDB token in my-tv/tmdb.properties."
                : "Titles from TMDB. Where to watch from JustWatch.", 11, regular, 0x66FFFFFF);
        credit.setPadding(dp(4), dp(14), 0, 0);
        credit.setMaxWidth(dp(280));
        left.addView(credit);
        body.addView(left, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        resultsScroll = new ScrollView(this) {
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
                return super.computeScrollDeltaToGetChildRectOnScreen(roomy(rect, 0, dp(20)));
            }
        };
        resultsScroll.setVerticalScrollBarEnabled(false);
        // The list clips at its edges, so scrolled cards never slide over the keyboard or the field; each row's
        // padding, inside that edge, leaves room for the focus zoom.
        resultsScroll.setClipToPadding(false);
        resultsScroll.setPadding(0, 0, 0, dp(28));
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setClipChildren(false);
        resultsScroll.addView(results);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rLp.leftMargin = dp(28);
        body.addView(resultsScroll, rLp);

        listening = listeningOverlay();
        root.addView(listening, matchParent());
        setContentView(root);

        showQuery();
        runSearch();
        View first = firstFocusable(keyboard);
        if (first != null) first.requestFocus();
        if (getIntent().getBooleanExtra(EXTRA_VOICE, false)) main.postDelayed(this::startVoice, 300);
    }

    // ---- the field and the keyboard ----------------------------------------------------------------

    private View field() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.HORIZONTAL);
        f.setGravity(Gravity.CENTER_VERTICAL);
        f.setPadding(dp(20), 0, dp(20), 0);
        f.setBackground(glass(28, false));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_search);
        icon.setColorFilter(0xB3FFFFFF);
        f.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        query = text("", 24, light, Color.WHITE);
        query.setSingleLine(true);
        query.setEllipsize(android.text.TextUtils.TruncateAt.START);
        LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        qLp.leftMargin = dp(14);
        f.addView(query, qLp);
        caret = new View(this);
        caret.setBackgroundColor(0xE6FFFFFF);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(dp(2), dp(26));
        cLp.leftMargin = dp(2);
        f.addView(caret, cLp);
        ObjectAnimator blink = ObjectAnimator.ofFloat(caret, View.ALPHA, 1f, 1f, 0f, 0f);
        blink.setDuration(1000);
        blink.setRepeatCount(ValueAnimator.INFINITE);
        blink.start();
        return f;
    }

    private static final String[] ROWS = {"abcdef", "ghijkl", "mnopqr", "stuvwx", "yz1234", "567890"};

    /** A 6×6 grid of letters and digits with Space, Delete and Clear under it, like Google TV's own. */
    private LinearLayout keyboard() {
        LinearLayout kb = new LinearLayout(this);
        kb.setOrientation(LinearLayout.VERTICAL);
        kb.setPadding(dp(12), dp(12), dp(12), dp(12));
        kb.setBackground(glass(22, false));
        kb.setClipChildren(false);
        kb.setClipToPadding(false);
        int key = dp(40), gap = dp(6);
        for (String row : ROWS) {
            LinearLayout r = keyRow(kb, gap);
            for (char c : row.toCharArray()) {
                String s = String.valueOf(c);
                r.addView(key(s.toUpperCase(Locale.ROOT), 17, v -> type(s)), keyLp(key, key, gap));
            }
        }
        LinearLayout r = keyRow(kb, 0);
        int wide = key * 2 + gap;
        r.addView(key("Space", 13, v -> type(" ")), keyLp(wide, key, gap));
        View del = key("Delete", 13, v -> deleteOne());
        del.setOnLongClickListener(v -> {
            clearAll();
            return true;
        });
        r.addView(del, keyLp(wide, key, gap));
        r.addView(key("Clear", 13, v -> clearAll()), keyLp(wide, key, gap));
        return kb;
    }

    private LinearLayout keyRow(LinearLayout kb, int bottomGap) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = bottomGap;
        kb.addView(r, lp);
        return r;
    }

    private LinearLayout.LayoutParams keyLp(int w, int h, int gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.rightMargin = gap;
        return lp;
    }

    private TextView key(String label, float sp, View.OnClickListener click) {
        TextView k = text(label, sp, medium, Color.WHITE);
        k.setGravity(Gravity.CENTER);
        k.setFocusable(true);
        k.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(10 * density);
        bg.setColor(0x1AFFFFFF);
        k.setBackground(bg);
        k.setOnFocusChangeListener((v, f) -> {
            bg.setColor(f ? 0xF2FFFFFF : 0x1AFFFFFF);
            k.setTextColor(f ? 0xFF111318 : Color.WHITE);
            v.animate().scaleX(f ? 1.12f : 1f).scaleY(f ? 1.12f : 1f).translationZ(f ? 6 * density : 0f)
                    .setDuration(100).start();
        });
        k.setOnClickListener(click);
        return k;
    }

    private void type(String s) {
        if (typed.length() >= 60 || (" ".equals(s) && (typed.length() == 0 || typed.charAt(typed.length() - 1) == ' '))) {
            return;
        }
        typed.append(s);
        edited();
    }

    private void deleteOne() {
        if (typed.length() == 0) return;
        typed.setLength(typed.length() - 1);
        edited();
    }

    private void clearAll() {
        if (typed.length() == 0) return;
        typed.setLength(0);
        edited();
    }

    /** Typing: the voice guesses no longer apply, and the results follow a moment after the last key. */
    private void edited() {
        heardAlso.clear();
        fallbacks.clear();
        showQuery();
        main.removeCallbacks(search);
        main.postDelayed(search, typed.length() == 0 ? 0 : 400);
    }

    private void setQuery(String s) {
        typed.setLength(0);
        typed.append(s.trim());
        showQuery();
    }

    private void showQuery() {
        boolean empty = typed.length() == 0;
        query.setText(empty ? "Search movies, shows and apps" : typed.toString());
        query.setTextColor(empty ? 0x80FFFFFF : Color.WHITE);
        // Before the hint when empty, after the words otherwise.
        ViewGroup f = (ViewGroup) query.getParent();
        f.removeView(caret);
        f.addView(caret, empty ? f.indexOfChild(query) : f.indexOfChild(query) + 1);
        LinearLayout.LayoutParams cLp = (LinearLayout.LayoutParams) caret.getLayoutParams();
        cLp.leftMargin = empty ? dp(14) : dp(2);
        ((LinearLayout.LayoutParams) query.getLayoutParams()).leftMargin = empty ? dp(4) : dp(14);
        query.requestLayout();
    }

    // ---- voice -------------------------------------------------------------------------------------

    private View micButton() {
        ImageView b = new ImageView(this);
        b.setImageResource(R.drawable.ic_mic);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setContentDescription("Search by voice");
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x33FFFFFF, 0x14FFFFFF});
        bg.setShape(GradientDrawable.OVAL);
        bg.setStroke(Math.max(1, dp(1)), 0x4DFFFFFF);
        b.setBackground(bg);
        b.setOnFocusChangeListener((v, f) -> {
            bg.setColors(f ? new int[]{0xF2FFFFFF, 0xD9FFFFFF} : new int[]{0x33FFFFFF, 0x14FFFFFF});
            b.setColorFilter(f ? 0xFF111318 : Color.WHITE);
            v.animate().scaleX(f ? 1.1f : 1f).scaleY(f ? 1.1f : 1f).setDuration(120).start();
        });
        b.setOnClickListener(v -> startVoice());
        return b;
    }

    /** The listening card: a mic that swells with your voice, and the words as they are recognised. */
    private FrameLayout listeningOverlay() {
        FrameLayout o = new FrameLayout(this);
        o.setBackgroundColor(0xD9050609);
        o.setVisibility(View.GONE);
        o.setClickable(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout disc = new FrameLayout(this);
        listenRing = new View(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(0x33FFFFFF);
        listenRing.setBackground(ring);
        disc.addView(listenRing, new FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER));
        ImageView m = new ImageView(this);
        m.setImageResource(R.drawable.ic_mic);
        m.setColorFilter(0xFF111318);
        m.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(Color.WHITE);
        m.setBackground(dot);
        disc.addView(m, new FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER));
        col.addView(disc, new LinearLayout.LayoutParams(dp(150), dp(150)));
        listenStatus = text("Listening…", 16, regular, 0xB3FFFFFF);
        listenStatus.setPadding(0, dp(8), 0, 0);
        listenStatus.setGravity(Gravity.CENTER);
        col.addView(listenStatus);
        listenHeard = text("", 32, light, Color.WHITE);
        listenHeard.setGravity(Gravity.CENTER);
        listenHeard.setPadding(dp(40), dp(14), dp(40), 0);
        listenHeard.setMaxLines(2);
        col.addView(listenHeard);
        TextView hint = text("Say a movie, show or app.  Back to cancel.", 13, regular, 0x73FFFFFF);
        hint.setPadding(0, dp(26), 0, 0);
        hint.setGravity(Gravity.CENTER);
        col.addView(hint);
        o.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        return o;
    }

    private void startVoice() {
        if (listeningNow) return;
        if (engine < 2 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, VOICE_REQUEST);
            return;
        }
        if (engine < 2 && recognizer == null) recognizer = createRecognizer();
        if (recognizer == null) {
            engine = 2;
            voiceScreen();
            return;
        }
        listeningNow = true;
        listenStatus.setText("Listening…");
        listenHeard.setText("");
        listenRing.setScaleX(1f);
        listenRing.setScaleY(1f);
        listening.setAlpha(0f);
        listening.setVisibility(View.VISIBLE);
        listening.animate().alpha(1f).setDuration(150).start();
        try {
            recognizer.startListening(speechIntent());
        } catch (RuntimeException e) {
            nextEngine();
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(request, perms, grants);
        if (request != VOICE_REQUEST) return;
        if (grants.length == 0 || grants[0] != PackageManager.PERMISSION_GRANTED) engine = 2;
        startVoice();
    }

    private SpeechRecognizer createRecognizer() {
        while (engine < 2) {
            try {
                if (engine == 0) {
                    Intent probe = new Intent(RecognitionService.SERVICE_INTERFACE).setComponent(KATNISS);
                    if (!getPackageManager().queryIntentServices(probe, 0).isEmpty()) {
                        return listen(SpeechRecognizer.createSpeechRecognizer(this, KATNISS));
                    }
                } else if (SpeechRecognizer.isRecognitionAvailable(this)) {
                    return listen(SpeechRecognizer.createSpeechRecognizer(this));
                }
            } catch (RuntimeException ignored) {
                // Next one.
            }
            engine++;
        }
        return null;
    }

    private Intent speechIntent() {
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                // Web search, not free form: tuned for short queries like titles and names.
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName())
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a movie, show or app");
    }

    private SpeechRecognizer listen(SpeechRecognizer r) {
        r.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) { listenStatus.setText("Listening…"); }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float db) {
                float s = 1f + Math.max(0f, Math.min(1f, db / 10f)) * 0.55f;
                listenRing.animate().scaleX(s).scaleY(s).setDuration(90).start();
            }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { listenStatus.setText("Searching…"); }
            @Override public void onError(int error) {
                if (!listeningNow) return;
                switch (error) {
                    case SpeechRecognizer.ERROR_NO_MATCH:
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                        endListening("Didn’t catch that. Press the mic to try again.");
                        break;
                    case SpeechRecognizer.ERROR_NETWORK:
                    case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                        endListening("No connection for voice right now.");
                        break;
                    default:
                        nextEngine();   // this recogniser can't serve us: the next one
                }
            }
            @Override public void onResults(Bundle b) {
                if (!listeningNow) return;
                List<String> heard = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                endListening(null);
                heard(heard);
            }
            @Override public void onPartialResults(Bundle b) {
                List<String> p = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (p != null && !p.isEmpty() && !p.get(0).trim().isEmpty()) listenHeard.setText(p.get(0));
            }
            @Override public void onEvent(int type, Bundle b) { }
        });
        return r;
    }

    private void nextEngine() {
        listeningNow = false;
        if (recognizer != null) recognizer.destroy();
        recognizer = null;
        listening.setVisibility(View.GONE);
        engine++;
        startVoice();
    }

    /** Closes the card, with a short message in it first when there is one. */
    private void endListening(String message) {
        listeningNow = false;
        if (message == null) {
            listening.animate().alpha(0f).setDuration(150).withEndAction(() -> listening.setVisibility(View.GONE)).start();
            return;
        }
        listenStatus.setText(message);
        listenRing.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
        main.postDelayed(() -> {
            if (!listeningNow) listening.animate().alpha(0f).setDuration(200)
                    .withEndAction(() -> listening.setVisibility(View.GONE)).start();
        }, 1600);
        mic.requestFocus();
    }

    private void cancelListening() {
        listeningNow = false;
        if (recognizer != null) recognizer.cancel();
        listening.setVisibility(View.GONE);
    }

    /** Last resort: Google's own speech screen, through the standard intent. */
    private void voiceScreen() {
        try {
            Intent i = speechIntent();
            i.removeExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS);
            startActivityForResult(i, VOICE_REQUEST);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Voice input isn’t available on this TV", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != VOICE_REQUEST || result != RESULT_OK || data == null) return;
        heard(data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS));
    }

    /** The recogniser's guesses, best first: search the first, keep the rest to offer and to fall back on. */
    private void heard(List<String> guesses) {
        List<String> distinct = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (guesses != null) {
            for (String g : guesses) {
                String t = g == null ? "" : g.trim();
                if (!t.isEmpty() && seen.add(t.toLowerCase(Locale.ROOT))) distinct.add(t);
            }
        }
        if (distinct.isEmpty()) return;
        setQuery(distinct.get(0));
        heardAlso.clear();
        heardAlso.addAll(distinct.subList(1, Math.min(4, distinct.size())));
        fallbacks.clear();
        fallbacks.addAll(heardAlso);
        remember(distinct.get(0));
        main.removeCallbacks(search);
        focusFirstResult = true;
        runSearch();
    }

    /** The remote's search (mic) key: listen again, wherever focus is. */
    @Override public boolean onSearchRequested() {
        startVoice();
        return true;
    }

    private void focusFirstIfAsked() {
        if (!focusFirstResult) return;
        View first = firstFocusable(results);
        if (first != null) {
            focusFirstResult = false;
            first.requestFocus();
        }
    }

    @Override protected void onPause() {
        super.onPause();
        if (listeningNow) cancelListening();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (recognizer != null) recognizer.destroy();
        net.shutdownNow();
    }

    // ---- recent searches ---------------------------------------------------------------------------

    private List<String> recent() {
        String s = getSharedPreferences(HomePrefs.NAME, MODE_PRIVATE).getString(RECENT, "");
        List<String> out = new ArrayList<>();
        for (String q : s.split("\n")) if (!q.trim().isEmpty()) out.add(q.trim());
        return out;
    }

    private void remember(String q) {
        if (q == null || q.trim().isEmpty()) return;
        List<String> list = recent();
        list.removeIf(x -> x.equalsIgnoreCase(q.trim()));
        list.add(0, q.trim());
        while (list.size() > 8) list.remove(list.size() - 1);
        getSharedPreferences(HomePrefs.NAME, MODE_PRIVATE).edit().putString(RECENT, String.join("\n", list)).apply();
    }

    /** Nothing typed: recent searches to pick again, or how to start. */
    private void showIdle() {
        List<String> recent = recent();
        if (recent.isEmpty()) {
            TextView t = text("Type with the keyboard, or press the mic and say a title.", 16, regular, 0x99FFFFFF);
            t.setPadding(dp(4), dp(10), 0, 0);
            results.addView(t);
            return;
        }
        results.addView(heading("Recent searches"));
        LinearLayout row = chipRow();
        for (String q : recent) row.addView(chip(q, () -> {
            setQuery(q);
            focusFirstResult = true;
            runSearch();
        }));
    }

    private LinearLayout chipRow() {
        HorizontalScrollView h = new HorizontalScrollView(this) {
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
                return super.computeScrollDeltaToGetChildRectOnScreen(roomy(rect, dp(24), 0));
            }
        };
        h.setHorizontalScrollBarEnabled(false);
        h.setHorizontalFadingEdgeEnabled(true);   // a card half scrolled out fades instead of being sliced
        h.setFadingEdgeLength(dp(48));
        h.setClipChildren(false);
        h.setClipToPadding(false);
        h.setPadding(0, dp(8), 0, dp(8));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setPadding(dp(6), 0, dp(40), 0);
        row.setClipToPadding(false);   // the focused card grows past the padding
        h.addView(row);
        results.addView(h);
        return row;
    }

    private View chip(String label, Runnable click) {
        TextView c = text(label, 15, regular, Color.WHITE);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(18), dp(9), dp(18), dp(9));
        c.setFocusable(true);
        c.setClickable(true);
        GradientDrawable bg = glass(20, false);
        c.setBackground(bg);
        c.setOnFocusChangeListener((v, f) -> {
            c.setBackground(f ? glass(20, true) : bg);
            c.setTextColor(f ? 0xFF111318 : Color.WHITE);
            v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(110).start();
        });
        c.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    private GradientDrawable glass(float radiusDp, boolean focused) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, focused
                ? new int[]{0xF2FFFFFF, 0xE0FFFFFF} : new int[]{0x26FFFFFF, 0x12FFFFFF});
        g.setCornerRadius(radiusDp * density);
        if (!focused) g.setStroke(Math.max(1, dp(1)), 0x40FFFFFF);
        return g;
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    // ---- searching ---------------------------------------------------------------------------------

    private void runSearch() {
        String q = typed.toString().trim();
        int gen = generation.incrementAndGet();
        results.removeAllViews();
        resultsScroll.scrollTo(0, 0);
        if (q.isEmpty()) {
            showIdle();
            return;
        }
        if (!heardAlso.isEmpty()) {
            results.addView(heading("Also heard"));
            LinearLayout chips = chipRow();
            for (String alt : heardAlso) chips.addView(chip(alt, () -> {
                String was = typed.toString();
                heardAlso.remove(alt);
                heardAlso.add(0, was);
                fallbacks.clear();
                setQuery(alt);
                remember(alt);
                focusFirstResult = true;
                runSearch();
            }));
        }
        List<ResolveInfo> apps = matchingApps(q);
        if (!apps.isEmpty()) {
            results.addView(heading("Apps"));
            LinearLayout row = shelf();
            for (ResolveInfo ri : apps) row.addView(appTile(ri));
            focusFirstIfAsked();
        }
        if (token.isEmpty()) return;
        net.execute(() -> {
            List<Title> titles = tmdbSearch(q);
            if (gen != generation.get()) return;
            main.post(() -> {
                if (gen != generation.get() || isDestroyed()) return;
                if (titles.isEmpty()) {
                    // A voice guess that finds nothing at all: the recogniser's next guess, if it had one.
                    if (apps.isEmpty() && !fallbacks.isEmpty()) {
                        String next = fallbacks.remove(0);
                        heardAlso.remove(next);
                        heardAlso.add(0, q);
                        setQuery(next);
                        runSearch();
                        return;
                    }
                    results.addView(heading("No movies or shows found for “" + q + "”"));
                    return;
                }
                results.addView(heading("Movies & shows"));
                LinearLayout row = shelf();
                List<View> cards = new ArrayList<>();
                for (Title t : titles) {
                    View card = posterCard(t);
                    row.addView(card);
                    cards.add(card);
                }
                focusFirstIfAsked();
                // Where to watch, per title, as it arrives.
                for (int i = 0; i < titles.size(); i++) {
                    Title t = titles.get(i);
                    View card = cards.get(i);
                    net.execute(() -> {
                        List<String> where = providers(t);
                        if (gen != generation.get()) return;
                        main.post(() -> {
                            t.apps.clear();
                            t.apps.addAll(where);
                            fillBadges(card, t);
                        });
                    });
                }
            });
        });
    }

    private List<ResolveInfo> matchingApps(String q) {
        PackageManager pm = getPackageManager();
        String needle = q.toLowerCase(Locale.ROOT);
        List<ResolveInfo> out = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            if (ri.activityInfo == null || getPackageName().equals(ri.activityInfo.packageName)) continue;
            String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
            if (label.contains(needle)) out.add(ri);
            if (out.size() >= 8) break;
        }
        return out;
    }

    private List<Title> tmdbSearch(String q) {
        List<Title> out = new ArrayList<>();
        try {
            JSONObject json = get("https://api.themoviedb.org/3/search/multi?include_adult=false&language=en-US&page=1&query="
                    + URLEncoder.encode(q, "UTF-8"));
            if (json == null) return out;
            JSONArray arr = json.optJSONArray("results");
            for (int i = 0; arr != null && i < arr.length() && out.size() < 14; i++) {
                JSONObject r = arr.getJSONObject(i);
                String kind = r.optString("media_type");
                if (!"movie".equals(kind) && !"tv".equals(kind)) continue;
                Title t = new Title();
                t.id = r.optInt("id");
                t.kind = kind;
                t.name = r.optString("movie".equals(kind) ? "title" : "name");
                String date = r.optString("movie".equals(kind) ? "release_date" : "first_air_date", "");
                t.year = date.length() >= 4 ? date.substring(0, 4) : "";
                String p = r.optString("poster_path", "");
                t.poster = p.isEmpty() || "null".equals(p) ? null : "https://image.tmdb.org/t/p/w342" + p;
                out.add(t);
            }
        } catch (Exception ignored) {
            // Offline or a bad token: apps still show.
        }
        return out;
    }

    /** Installed apps that stream (or show free with ads) this title in the US, in provider order. */
    private List<String> providers(Title t) {
        LinkedHashMap<String, Boolean> pkgs = new LinkedHashMap<>();
        java.util.Set<String> unpaid = HomePrefs.unpaidServices(this);
        try {
            JSONObject json = get("https://api.themoviedb.org/3/" + t.kind + "/" + t.id + "/watch/providers");
            JSONObject us = json == null ? null : json.optJSONObject("results");
            us = us == null ? null : us.optJSONObject("US");
            if (us != null) {
                for (String group : new String[]{"flatrate", "free", "ads"}) {
                    JSONArray arr = us.optJSONArray(group);
                    for (int i = 0; arr != null && i < arr.length(); i++) {
                        String pkg = PROVIDERS.get(arr.getJSONObject(i).optInt("provider_id"));
                        // Services nobody here pays for (Home settings) are left out, even when installed.
                        if (pkg != null && installed(pkg) && !unpaid.contains(pkg)) pkgs.put(pkg, true);
                    }
                }
            }
        } catch (Exception ignored) {
            // No badges then.
        }
        return new ArrayList<>(pkgs.keySet());
    }

    private JSONObject get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 2 * 1024 * 1024) out.write(buf, 0, n);
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally {
            c.disconnect();
        }
    }

    // ---- opening -----------------------------------------------------------------------------------

    private void pick(Title t) {
        remember(typed.toString());
        if (t.apps.isEmpty()) {
            Toast.makeText(this, t.name + " isn’t streaming on your apps", Toast.LENGTH_SHORT).show();
            return;
        }
        if (t.apps.size() == 1) {
            openIn(t.apps.get(0), t.name);
            return;
        }
        String[] names = new String[t.apps.size()];
        for (int i = 0; i < names.length; i++) names[i] = label(t.apps.get(i));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Watch " + t.name + " on")
                .setItems(names, (d, which) -> openIn(t.apps.get(which), t.name))
                .show();
    }

    /** The app's own search with the title when it takes one, else a search link it handles, else the app. */
    private void openIn(String pkg, String query) {
        PackageManager pm = getPackageManager();
        if (query.isEmpty()) {
            Intent app = ContinueWatching.launchIntent(pm, pkg);
            if (app != null) startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        Intent[] tries = {
                new Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, query),
                SEARCH_LINKS.containsKey(pkg) ? new Intent(Intent.ACTION_VIEW,
                        Uri.parse(SEARCH_LINKS.get(pkg) + Uri.encode(query))).setPackage(pkg) : null,
                ContinueWatching.launchIntent(pm, pkg),
        };
        for (Intent i : tries) {
            if (i == null || i.resolveActivity(pm) == null) continue;
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (RuntimeException ignored) {
                // Try the next way in.
            }
        }
        Toast.makeText(this, "Couldn’t open " + label(pkg), Toast.LENGTH_SHORT).show();
    }

    // ---- views -------------------------------------------------------------------------------------

    private TextView heading(String s) {
        TextView t = text(s, 17, medium, Color.WHITE);
        t.setPadding(dp(4), results.getChildCount() == 0 ? dp(2) : dp(18), 0, dp(2));
        return t;
    }

    /** A focused card scrolls in with room around it, so its focus zoom is never cut by the row's edge. */
    private static Rect roomy(Rect rect, int dx, int dy) {
        Rect r = new Rect(rect);
        r.inset(-dx, -dy);
        return r;
    }

    private LinearLayout shelf() {
        HorizontalScrollView h = new HorizontalScrollView(this) {
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
                return super.computeScrollDeltaToGetChildRectOnScreen(roomy(rect, dp(24), 0));
            }
        };
        h.setHorizontalScrollBarEnabled(false);
        h.setHorizontalFadingEdgeEnabled(true);   // a card half scrolled out fades instead of being sliced
        h.setFadingEdgeLength(dp(48));
        h.setClipChildren(false);
        h.setClipToPadding(false);
        h.setPadding(0, dp(12), 0, dp(14));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setPadding(dp(10), 0, dp(40), 0);
        row.setClipToPadding(false);   // the focused card grows past the padding
        h.addView(row);
        results.addView(h);
        return row;
    }

    private View appTile(ResolveInfo ri) {
        PackageManager pm = getPackageManager();
        FrameLayout card = card(dp(160), dp(90));
        ImageView img = new ImageView(this);
        Drawable banner = ri.activityInfo.loadBanner(pm);
        if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
        img.setImageDrawable(banner != null ? banner : ri.loadIcon(pm));
        img.setScaleType(banner != null ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        String pkg = ri.activityInfo.packageName;
        card.setOnClickListener(v -> {
            remember(typed.toString());
            openIn(pkg, "");
        });
        card.setContentDescription(String.valueOf(ri.loadLabel(pm)));
        return withGap(card);
    }

    private View posterCard(Title t) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        FrameLayout card = card(dp(116), dp(174));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        if (t.poster == null) {
            TextView name = text(t.name, 16, medium, Color.WHITE);
            name.setGravity(Gravity.CENTER);
            name.setPadding(dp(10), 0, dp(10), 0);
            card.addView(name, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        } else {
            loadPoster(t.poster, img);
        }
        LinearLayout badges = new LinearLayout(this);
        badges.setOrientation(LinearLayout.HORIZONTAL);
        badges.setTag("badges");
        badges.setPadding(dp(6), 0, dp(6), dp(6));
        card.addView(badges, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START));
        card.setOnClickListener(v -> pick(t));
        card.setContentDescription(t.name);
        col.addView(card);
        TextView label = text(t.name + (t.year.isEmpty() ? "" : "  ·  " + t.year), 13, regular, 0xD9FFFFFF);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setPadding(0, dp(10), 0, 0);
        col.addView(label, new LinearLayout.LayoutParams(dp(116), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView where = text(" ", 11, regular, 0x99FFFFFF);
        where.setTag("where");
        where.setSingleLine(true);
        where.setEllipsize(android.text.TextUtils.TruncateAt.END);
        where.setPadding(0, dp(4), 0, 0);
        col.addView(where, new LinearLayout.LayoutParams(dp(116), ViewGroup.LayoutParams.WRAP_CONTENT));
        return withGap(col);
    }

    /** The apps that have it: small app icons on the poster and their names under it. */
    private void fillBadges(View cell, Title t) {
        LinearLayout badges = cell.findViewWithTag("badges");
        TextView where = cell.findViewWithTag("where");
        if (badges == null || where == null) return;
        badges.removeAllViews();
        PackageManager pm = getPackageManager();
        StringBuilder names = new StringBuilder();
        for (String pkg : t.apps) {
            ImageView icon = new ImageView(this);
            try {
                icon.setImageDrawable(pm.getApplicationIcon(pkg));
            } catch (PackageManager.NameNotFoundException ignored) {
                continue;
            }
            GradientDrawable chip = new GradientDrawable();
            chip.setCornerRadius(8 * density);
            chip.setColor(0xCC000000);
            icon.setBackground(chip);
            icon.setPadding(dp(3), dp(3), dp(3), dp(3));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(28), dp(28));
            lp.rightMargin = dp(4);
            badges.addView(icon, lp);
            if (names.length() > 0) names.append(", ");
            names.append(label(pkg));
        }
        where.setText(t.apps.isEmpty() ? "Not on your apps" : names.toString());
    }

    private FrameLayout card(int w, int h) {
        FrameLayout card = new FrameLayout(this);
        card.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        card.setFocusable(true);
        card.setClickable(true);
        float r = 12 * density;
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(r);
        bg.setColor(0xFF22252E);
        card.setBackground(bg);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(r);
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(dp(3), Color.WHITE);
        ring.setAlpha(0);
        card.setForeground(ring);
        card.setOnFocusChangeListener((v, f) -> {
            ring.setAlpha(f ? 255 : 0);
            v.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).translationZ(f ? 14 * density : 0f)
                    .setDuration(140).start();
        });
        return card;
    }

    private View withGap(View v) {
        LinearLayout.LayoutParams lp = v.getLayoutParams() instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) v.getLayoutParams()
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(20);
        v.setLayoutParams(lp);
        return v;
    }

    private void loadPoster(String url, ImageView into) {
        Bitmap cached = POSTERS.get(url);
        if (cached != null) {
            into.setImageBitmap(cached);
            return;
        }
        into.setTag(url);
        net.execute(() -> {
            Bitmap bmp = null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(10000);
                if (c.getResponseCode() == 200) {
                    try (InputStream in = c.getInputStream()) {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inPreferredConfig = Bitmap.Config.RGB_565;
                        bmp = BitmapFactory.decodeStream(in, null, o);
                    }
                }
            } catch (Exception ignored) {
                // Name-only card then.
            } finally {
                if (c != null) c.disconnect();
            }
            if (bmp == null) return;
            POSTERS.put(url, bmp);
            Bitmap done = bmp;
            main.post(() -> {
                if (url.equals(into.getTag())) into.setImageBitmap(done);
            });
        });
    }

    private View firstFocusable(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.isFocusable()) return c;
            if (c instanceof ViewGroup) {
                View f = firstFocusable((ViewGroup) c);
                if (f != null) return f;
            }
        }
        return null;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (listening.getVisibility() == View.VISIBLE) {
            // The card takes every key; Back (or OK) stops listening.
            if (e.getAction() == KeyEvent.ACTION_UP && (code == KeyEvent.KEYCODE_BACK
                    || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
                if (listeningNow) cancelListening();
                else listening.setVisibility(View.GONE);
                mic.requestFocus();
            }
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_VOICE_ASSIST) {
                startVoice();
                return true;
            }
            // A real keyboard (or a remote with letters) types straight in.
            if (code == KeyEvent.KEYCODE_DEL) {
                deleteOne();
                return true;
            }
            int ch = e.getUnicodeChar();
            if (ch > 0 && !e.isCtrlPressed() && (Character.isLetterOrDigit(ch) || ch == ' ')) {
                type(String.valueOf((char) Character.toLowerCase(ch)));
                return true;
            }
            // Back from the results returns to the keyboard first, as on Google TV.
            if (code == KeyEvent.KEYCODE_BACK && results.hasFocus()) {
                return true;
            }
        }
        if (e.getAction() == KeyEvent.ACTION_UP && code == KeyEvent.KEYCODE_BACK && results.hasFocus()) {
            View first = firstFocusable(keyboard);
            if (first != null) first.requestFocus();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getApplicationInfo(pkg, 0);
            return getPackageManager().getApplicationEnabledSetting(pkg)
                    != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private String label(String pkg) {
        PackageManager pm = getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private TextView text(String s, float sp, Typeface tf, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private Typeface font(int res) {
        try {
            return getResources().getFont(res);
        } catch (RuntimeException e) {
            return Typeface.DEFAULT;
        }
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
