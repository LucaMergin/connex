package si.ell.soundcheck;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQUEST_RECORD_AUDIO = 1001;
    private static final int SAMPLE_RATE = 44100;
    private static final int FFT_SIZE = 4096;
    private static final String PREFS = "soundcheck_v02";
    private static final String KEY_HISTORY = "session_history";
    private static final int MAX_HISTORY = 12;
    private static final long UI_UPDATE_INTERVAL_MS = 650;
    private static final long STARTUP_SETTLE_MS = 1800;
    private static final long TREND_INTERVAL_MS = 1000;
    private static final double METRIC_ALPHA = 0.16;
    private static final double DOMINANT_ALPHA = 0.10;
    private static final double PEAK_ATTACK_ALPHA = 0.42;
    private static final double PEAK_RELEASE_ALPHA = 0.10;

    private static final int BG = Color.rgb(16, 19, 26);
    private static final int CARD = Color.rgb(28, 33, 43);
    private static final int CARD_2 = Color.rgb(35, 42, 54);
    private static final int CARD_3 = Color.rgb(44, 52, 66);
    private static final int TEXT = Color.rgb(242, 245, 248);
    private static final int MUTED = Color.rgb(165, 176, 190);
    private static final int ACCENT = Color.rgb(102, 227, 196);
    private static final int WARNING = Color.rgb(255, 190, 92);
    private static final int DANGER = Color.rgb(255, 104, 116);
    private static final int INFO = Color.rgb(112, 174, 255);

    private static final double[] EQ_FREQUENCIES = {
            31, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400,
            500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000,
            5000, 6300, 8000, 10000, 12500, 16000
    };

    private final String[] modes = {
            "Celotni bend", "Vokal", "Bas kitara", "Električna kitara",
            "Akustična kitara", "Kick", "Snare", "Celotni bobni",
            "Klaviature", "Monitor", "Govor"
    };

    private final String[] sensitivities = {
            "Normalna", "Visoka – hitrejša opozorila", "Nizka – samo izrazite težave"
    };

    private Spinner modeSpinner;
    private Spinner sensitivitySpinner;
    private Button startButton;
    private Button referenceButton;
    private Button shareButton;
    private TextView stateText;
    private TextView scoreText;
    private TextView timerText;
    private TextView rmsText;
    private TextView peakText;
    private TextView dominantText;
    private TextView noteText;
    private TextView recommendationText;
    private TextView guideText;
    private TextView sessionText;
    private TextView primaryIssueText;
    private TextView primaryActionText;
    private TextView comparisonText;
    private LinearLayout settingsPanel;
    private Button compareButton;
    private Button graphModeButton;
    private TrendView trendView;
    private boolean advancedSpectrum = false;
    private long lastTrendPointAt = 0;
    private int referenceScore = 0;
    private LinearLayout anomalyContainer;
    private LinearLayout historyContainer;
    private SpectrumView spectrumView;

    private volatile boolean running = false;
    private volatile int selectedModeIndex = 0;
    private volatile int selectedSensitivityIndex = 0;
    private AudioRecord recorder;
    private Thread audioThread;
    private double lastPeakHz = 0;
    private int stablePeakFrames = 0;
    private final double[] smoothedBands = new double[6];
    private boolean bandsInitialized = false;

    // Umirjen prikaz: meritve tečejo hitro, uporabniški vmesnik pa dobi
    // počasneje osvežene in časovno stabilizirane rezultate.
    private final EvidenceLatch clippingLatch = new EvidenceLatch(6, 1, 3, 1);
    private final EvidenceLatch humLatch = new EvidenceLatch(7, 2, 2, 1);
    private final EvidenceLatch feedbackLatch = new EvidenceLatch(8, 2, 2, 1);
    private final EvidenceLatch mudLatch = new EvidenceLatch(7, 2, 2, 1);
    private final EvidenceLatch harshLatch = new EvidenceLatch(7, 2, 2, 1);
    private final EvidenceLatch hissLatch = new EvidenceLatch(7, 2, 2, 1);
    private final EvidenceLatch lowHeavyLatch = new EvidenceLatch(7, 2, 2, 1);
    private final EvidenceLatch quietLatch = new EvidenceLatch(6, 1, 2, 1);
    private boolean displayInitialized = false;
    private double displayRmsDb = -120;
    private double displayPeakDb = -120;
    private double displayDominantHz = 440;
    private double displayProminence = 0;
    private double displayScore = 100;
    private double[] displayBands = new double[6];
    private float[] displaySpectrum = new float[0];
    private long lastUiPostAt = 0;
    private String lastAnomalySignature = "";
    private String lastRecommendationText = "";

    private long sessionStartedAt = 0;
    private int sessionMinScore = 100;
    private double sessionMaxPeakDb = -120;
    private int sessionClipFrames = 0;
    private int sessionHumFrames = 0;
    private int sessionFeedbackFrames = 0;
    private int sessionMudFrames = 0;
    private int sessionHarshFrames = 0;
    private int sessionHissFrames = 0;
    private int renderedFrames = 0;
    private AnalysisResult latestResult;
    private SessionRecord latestSession;
    private double[] referenceBands;
    private double referenceRmsDb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        renderHistory();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(34));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(topRow, matchWrap());

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        topRow.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        brand.addView(label("SOUNDCHECK ASSISTANT", 12, ACCENT, true), matchWrap());
        TextView subtitle = label("Pametnejša in mirnejša tonska vaja", 13, MUTED, false);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.topMargin = dp(3);
        brand.addView(subtitle, subtitleParams);

        TextView version = label("v0.3.2", 12, BG, true);
        version.setGravity(Gravity.CENTER);
        version.setBackground(rounded(ACCENT, 12));
        topRow.addView(version, new LinearLayout.LayoutParams(dp(58), dp(30)));

        LinearLayout hero = card();
        LinearLayout.LayoutParams heroParams = matchWrap();
        heroParams.topMargin = dp(18);
        root.addView(hero, heroParams);

        LinearLayout heroTop = new LinearLayout(this);
        heroTop.setOrientation(LinearLayout.HORIZONTAL);
        heroTop.setGravity(Gravity.CENTER_VERTICAL);
        hero.addView(heroTop, matchWrap());

        LinearLayout heroText = new LinearLayout(this);
        heroText.setOrientation(LinearLayout.VERTICAL);
        heroTop.addView(heroText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        heroText.addView(label("TRENUTNO STANJE", 11, MUTED, true), matchWrap());
        stateText = label("Pripravljeno", 20, TEXT, true);
        LinearLayout.LayoutParams stateParams = matchWrap();
        stateParams.topMargin = dp(5);
        heroText.addView(stateText, stateParams);
        timerText = label("00:00", 13, MUTED, true);
        LinearLayout.LayoutParams timerParams = matchWrap();
        timerParams.topMargin = dp(5);
        heroText.addView(timerText, timerParams);

        scoreText = label("—", 34, ACCENT, true);
        scoreText.setGravity(Gravity.CENTER);
        scoreText.setBackground(rounded(CARD_2, 20));
        heroTop.addView(scoreText, new LinearLayout.LayoutParams(dp(82), dp(78)));

        TextView issueCaption = label("NAJVEČJA TEŽAVA", 11, WARNING, true);
        LinearLayout.LayoutParams issueCapParams = matchWrap();
        issueCapParams.topMargin = dp(18);
        hero.addView(issueCaption, issueCapParams);
        primaryIssueText = label("Začni analizo, da aplikacija poišče najpomembnejšo težavo.", 17, TEXT, true);
        primaryIssueText.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams issueParams = matchWrap();
        issueParams.topMargin = dp(7);
        hero.addView(primaryIssueText, issueParams);

        TextView actionCaption = label("PRVI KORAK", 11, ACCENT, true);
        LinearLayout.LayoutParams actionCapParams = matchWrap();
        actionCapParams.topMargin = dp(15);
        hero.addView(actionCaption, actionCapParams);
        primaryActionText = label("Telefon postavi na mesto poslušanja tonskega mojstra.", 15, MUTED, false);
        primaryActionText.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams actionParams = matchWrap();
        actionParams.topMargin = dp(7);
        hero.addView(primaryActionText, actionParams);

        startButton = makeButton("ZAČNI TONSKO VAJO", ACCENT, BG);
        startButton.setOnClickListener(v -> toggleAnalysis());
        LinearLayout.LayoutParams startParams = matchHeight(dp(60));
        startParams.topMargin = dp(17);
        root.addView(startButton, startParams);

        LinearLayout quickRow = new LinearLayout(this);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams quickParams = matchHeight(dp(46));
        quickParams.topMargin = dp(10);
        root.addView(quickRow, quickParams);

        Button settingsButton = makeSmallButton("NASTAVITVE");
        settingsButton.setOnClickListener(v -> {
            boolean show = settingsPanel.getVisibility() != View.VISIBLE;
            settingsPanel.setVisibility(show ? View.VISIBLE : View.GONE);
            settingsButton.setText(show ? "SKRIJ" : "NASTAVITVE");
        });
        quickRow.addView(settingsButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        Button guideButton = makeSmallButton("VODIČ");
        guideButton.setOnClickListener(v -> showGuideDialog());
        LinearLayout.LayoutParams guideButtonParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        guideButtonParams.leftMargin = dp(8);
        quickRow.addView(guideButton, guideButtonParams);

        Button historyButton = makeSmallButton("ZGODOVINA");
        historyButton.setOnClickListener(v -> showHistoryDialog());
        LinearLayout.LayoutParams historyButtonParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        historyButtonParams.leftMargin = dp(8);
        quickRow.addView(historyButton, historyButtonParams);

        settingsPanel = card();
        settingsPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams settingsParams = matchWrap();
        settingsParams.topMargin = dp(10);
        root.addView(settingsPanel, settingsParams);
        settingsPanel.addView(label("Vir ali instrument", 12, MUTED, true), matchWrap());
        modeSpinner = new Spinner(this);
        modeSpinner.setPopupBackgroundDrawable(rounded(CARD_2, 14));
        modeSpinner.setAdapter(new DarkAdapter(this, modes));
        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { updateGuide(position); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        LinearLayout.LayoutParams modeParams = matchHeight(dp(52));
        modeParams.topMargin = dp(8);
        settingsPanel.addView(modeSpinner, modeParams);
        TextView sensitivityLabel = label("Občutljivost", 12, MUTED, true);
        LinearLayout.LayoutParams sensitivityLabelParams = matchWrap();
        sensitivityLabelParams.topMargin = dp(13);
        settingsPanel.addView(sensitivityLabel, sensitivityLabelParams);
        sensitivitySpinner = new Spinner(this);
        sensitivitySpinner.setPopupBackgroundDrawable(rounded(CARD_2, 14));
        sensitivitySpinner.setAdapter(new DarkAdapter(this, sensitivities));
        LinearLayout.LayoutParams sensitivityParams = matchHeight(dp(52));
        sensitivityParams.topMargin = dp(8);
        settingsPanel.addView(sensitivitySpinner, sensitivityParams);

        LinearLayout spectrumHeader = new LinearLayout(this);
        spectrumHeader.setOrientation(LinearLayout.HORIZONTAL);
        spectrumHeader.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams spectrumHeaderParams = matchWrap();
        spectrumHeaderParams.topMargin = dp(20);
        root.addView(spectrumHeader, spectrumHeaderParams);
        spectrumHeader.addView(label("Frekvenčni graf", 17, TEXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        graphModeButton = makeSmallButton("PREPROST");
        graphModeButton.setOnClickListener(v -> {
            advancedSpectrum = !advancedSpectrum;
            graphModeButton.setText(advancedSpectrum ? "NAPREDNI" : "PREPROST");
            spectrumView.setSimpleMode(!advancedSpectrum);
        });
        spectrumHeader.addView(graphModeButton, new LinearLayout.LayoutParams(dp(98), dp(38)));

        spectrumView = new SpectrumView(this);
        spectrumView.setSimpleMode(true);
        spectrumView.setBackground(rounded(CARD, 18));
        LinearLayout.LayoutParams spectrumParams = matchHeight(dp(225));
        spectrumParams.topMargin = dp(9);
        root.addView(spectrumView, spectrumParams);

        TextView spectrumHint = label("LIVE brez glajenja · dotakni se grafa za natančen Hz in dBFS", 12, MUTED, false);
        LinearLayout.LayoutParams spectrumHintParams = matchWrap();
        spectrumHintParams.topMargin = dp(6);
        root.addView(spectrumHint, spectrumHintParams);

        TextView trendTitle = label("Zadnjih 60 sekund", 17, TEXT, true);
        LinearLayout.LayoutParams trendTitleParams = matchWrap();
        trendTitleParams.topMargin = dp(18);
        root.addView(trendTitle, trendTitleParams);
        trendView = new TrendView(this);
        trendView.setBackground(rounded(CARD, 18));
        LinearLayout.LayoutParams trendParams = matchHeight(dp(170));
        trendParams.topMargin = dp(9);
        root.addView(trendView, trendParams);

        LinearLayout metricsCard = card();
        LinearLayout.LayoutParams metricsParams = matchWrap();
        metricsParams.topMargin = dp(14);
        root.addView(metricsCard, metricsParams);
        LinearLayout meterRow1 = new LinearLayout(this);
        meterRow1.setOrientation(LinearLayout.HORIZONTAL);
        metricsCard.addView(meterRow1, matchWrap());
        rmsText = meterBox(meterRow1, "RMS", "— dBFS");
        peakText = meterBox(meterRow1, "PEAK", "— dBFS");
        LinearLayout meterRow2 = new LinearLayout(this);
        meterRow2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams meterRow2Params = matchWrap();
        meterRow2Params.topMargin = dp(8);
        metricsCard.addView(meterRow2, meterRow2Params);
        dominantText = meterBox(meterRow2, "VRH", "— Hz");
        noteText = meterBox(meterRow2, "TON / PEQ", "—");

        anomalyContainer = new LinearLayout(this);
        anomalyContainer.setOrientation(LinearLayout.HORIZONTAL);
        anomalyContainer.setGravity(Gravity.CENTER_VERTICAL);
        HorizontalScrollView anomalyScroll = new HorizontalScrollView(this);
        anomalyScroll.setHorizontalScrollBarEnabled(false);
        anomalyScroll.addView(anomalyContainer, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams anomalyParams = matchHeight(dp(46));
        anomalyParams.topMargin = dp(12);
        metricsCard.addView(anomalyScroll, anomalyParams);
        renderAnomalies(null);

        LinearLayout recommendationCard = card();
        LinearLayout.LayoutParams recommendationParams = matchWrap();
        recommendationParams.topMargin = dp(14);
        root.addView(recommendationCard, recommendationParams);
        recommendationCard.addView(label("NAJVEČ TRIJE KORAKI", 11, ACCENT, true), matchWrap());
        recommendationText = label("Začni analizo. Aplikacija bo prikazala največ tri najbolj smiselne korake.", 15, TEXT, false);
        recommendationText.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams recommendationTextParams = matchWrap();
        recommendationTextParams.topMargin = dp(10);
        recommendationCard.addView(recommendationText, recommendationTextParams);

        LinearLayout compareCard = card();
        LinearLayout.LayoutParams compareCardParams = matchWrap();
        compareCardParams.topMargin = dp(14);
        root.addView(compareCard, compareCardParams);
        compareCard.addView(label("PRIMERJAVA PREJ / ZDAJ", 11, INFO, true), matchWrap());
        comparisonText = label("Shrani zvok pred spremembo, popravi mešalno mizo in nato primerjaj rezultat.", 14, MUTED, false);
        comparisonText.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams comparisonParams = matchWrap();
        comparisonParams.topMargin = dp(8);
        compareCard.addView(comparisonText, comparisonParams);
        LinearLayout compareRow = new LinearLayout(this);
        compareRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams compareRowParams = matchHeight(dp(48));
        compareRowParams.topMargin = dp(12);
        compareCard.addView(compareRow, compareRowParams);
        referenceButton = makeButton("SHRANI PREJ", CARD_3, TEXT);
        referenceButton.setEnabled(false);
        referenceButton.setAlpha(0.55f);
        referenceButton.setOnClickListener(v -> saveReference());
        compareRow.addView(referenceButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        compareButton = makeButton("PRIMERJAJ ZDAJ", CARD_3, TEXT);
        compareButton.setEnabled(false);
        compareButton.setAlpha(0.55f);
        compareButton.setOnClickListener(v -> compareNow());
        LinearLayout.LayoutParams compareButtonParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        compareButtonParams.leftMargin = dp(8);
        compareRow.addView(compareButton, compareButtonParams);

        LinearLayout sessionCard = card();
        LinearLayout.LayoutParams sessionParams = matchWrap();
        sessionParams.topMargin = dp(14);
        root.addView(sessionCard, sessionParams);
        sessionCard.addView(label("ZADNJA SEJA", 11, WARNING, true), matchWrap());
        sessionText = label("Povzetek se prikaže po zaključeni tonski vaji.", 14, TEXT, false);
        sessionText.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams sessionTextParams = matchWrap();
        sessionTextParams.topMargin = dp(8);
        sessionCard.addView(sessionText, sessionTextParams);
        shareButton = makeButton("DELI POVZETEK", CARD_3, TEXT);
        shareButton.setEnabled(false);
        shareButton.setAlpha(0.55f);
        shareButton.setOnClickListener(v -> shareLatestSession());
        LinearLayout.LayoutParams shareParams = matchHeight(dp(46));
        shareParams.topMargin = dp(10);
        sessionCard.addView(shareButton, shareParams);

        TextView warning = label(
                "Telefon ni kalibriran merilni mikrofon. Priporočila uporabljaj kot pomoč in vedno preveri spremembe s PFL/Solo ter poslušanjem.",
                12, MUTED, false);
        warning.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams warningParams = matchWrap();
        warningParams.topMargin = dp(15);
        root.addView(warning, warningParams);

        guideText = label("", 14, TEXT, false);
        historyContainer = new LinearLayout(this);
        historyContainer.setOrientation(LinearLayout.VERTICAL);
        updateGuide(0);
        setContentView(scroll);
    }

    private TextView meterBox(LinearLayout parent, String caption, String initial) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(6), dp(10), dp(6), dp(10));
        box.setBackground(rounded(CARD_2, 14));

        TextView cap = label(caption, 10, MUTED, true);
        cap.setGravity(Gravity.CENTER);
        box.addView(cap);

        TextView value = label(initial, 14, TEXT, true);
        value.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams valueParams = matchWrap();
        valueParams.topMargin = dp(4);
        box.addView(value, valueParams);

        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(0, dp(72), 1f);
        if (parent.getChildCount() > 0) boxParams.leftMargin = dp(8);
        parent.addView(box, boxParams);
        return value;
    }

    private Button makeButton(String text, int background, int foreground) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(foreground);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackground(rounded(background, 16));
        return button;
    }

    private Button makeSmallButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(MUTED);
        button.setTextSize(11);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setPadding(dp(5), 0, dp(5), 0);
        button.setBackground(rounded(CARD_3, 12));
        return button;
    }

    private void toggleAnalysis() {
        if (running) {
            stopAnalysis(true);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
        } else {
            startAnalysis();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startAnalysis();
            } else {
                Toast.makeText(this, "Brez dovoljenja za mikrofon analiza ni mogoča.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startAnalysis() {
        if (running) return;
        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            showAudioError("Telefon ne podpira zahtevanega zvočnega formata.");
            return;
        }

        recorder = buildRecorder(MediaRecorder.AudioSource.UNPROCESSED, Math.max(minBuffer, FFT_SIZE * 4));
        if (recorder == null || recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            if (recorder != null) recorder.release();
            recorder = buildRecorder(MediaRecorder.AudioSource.MIC, Math.max(minBuffer, FFT_SIZE * 4));
        }
        if (recorder == null || recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            showAudioError("Mikrofona ni bilo mogoče zagnati. Zapri druge aplikacije, ki uporabljajo mikrofon.");
            return;
        }

        try {
            recorder.startRecording();
        } catch (Exception e) {
            recorder.release();
            recorder = null;
            showAudioError("Mikrofona ni bilo mogoče zagnati: " + e.getMessage());
            return;
        }

        selectedModeIndex = modeSpinner.getSelectedItemPosition();
        selectedSensitivityIndex = sensitivitySpinner.getSelectedItemPosition();
        running = true;
        stablePeakFrames = 0;
        bandsInitialized = false;
        resetDisplayStabilization();
        referenceBands = null;
        referenceScore = 0;
        latestResult = null;
        lastTrendPointAt = 0;
        if (trendView != null) trendView.clear();
        sessionStartedAt = SystemClock.elapsedRealtime();
        sessionMinScore = 100;
        sessionMaxPeakDb = -120;
        sessionClipFrames = 0;
        sessionHumFrames = 0;
        sessionFeedbackFrames = 0;
        sessionMudFrames = 0;
        sessionHarshFrames = 0;
        sessionHissFrames = 0;
        renderedFrames = 0;

        modeSpinner.setEnabled(false);
        sensitivitySpinner.setEnabled(false);
        startButton.setText("USTAVI IN SHRANI SEJO");
        startButton.setTextColor(TEXT);
        startButton.setBackground(rounded(DANGER, 16));
        referenceButton.setEnabled(true);
        referenceButton.setAlpha(1f);
        referenceButton.setText("SHRANI PREJ");
        compareButton.setEnabled(false);
        compareButton.setAlpha(0.55f);
        comparisonText.setText("Shrani zvok pred spremembo, popravi mešalno mizo in nato primerjaj rezultat.");
        shareButton.setEnabled(false);
        shareButton.setAlpha(0.55f);
        stateText.setText("Poslušam …");
        primaryIssueText.setText("Analiza se stabilizira …");
        primaryActionText.setText("Predvajaj normalen del skladbe in ne premikaj telefona.");
        stateText.setTextColor(ACCENT);
        scoreText.setText("—");
        timerText.setText("00:00");
        sessionText.setText("Seja poteka. Ob ustavitvi se samodejno shrani lokalni povzetek.");
        recommendationText.setText("Predvajaj normalen del skladbe. Za zanesljivejšo diagnozo naj telefon ostane na istem mestu.");
        renderAnomalies(null);

        audioThread = new Thread(this::audioLoop, "SoundCheck-Audio");
        audioThread.start();
    }

    private AudioRecord buildRecorder(int source, int bufferBytes) {
        try {
            return new AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(bufferBytes)
                    .build();
        } catch (Exception ignored) {
            return null;
        }
    }

    private void audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        short[] samples = new short[FFT_SIZE];
        while (running && recorder != null) {
            int read;
            try {
                read = recorder.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
            } catch (Exception e) {
                read = -1;
            }
            if (read <= 0) continue;
            if (read < FFT_SIZE) Arrays.fill(samples, read, FFT_SIZE, (short) 0);

            AnalysisResult rawResult = analyze(samples);

            // Frekvenčni graf je namenoma popolnoma LIVE. Ne uporablja glajenja
            // preostalih meritev in se osveži pri vsakem novem FFT okviru.
            float[] liveSpectrum = Arrays.copyOf(rawResult.display, rawResult.display.length);
            double livePeakHz = rawResult.dominantHz;
            boolean liveFeedback = rawResult.feedback;
            runOnUiThread(() -> {
                if (running && spectrumView != null) {
                    spectrumView.setLevels(liveSpectrum, livePeakHz, liveFeedback);
                }
            });

            AnalysisResult result = stabilizeResult(rawResult);
            long now = SystemClock.elapsedRealtime();
            if (now - sessionStartedAt < STARTUP_SETTLE_MS) continue;
            if (lastUiPostAt != 0 && now - lastUiPostAt < UI_UPDATE_INTERVAL_MS) continue;
            lastUiPostAt = now;
            runOnUiThread(() -> renderResult(result));
        }
    }

    private AnalysisResult analyze(short[] input) {
        double[] re = new double[FFT_SIZE];
        double[] im = new double[FFT_SIZE];
        double sumSq = 0;
        int clipped = 0;
        double peak = 0;

        for (int i = 0; i < FFT_SIZE; i++) {
            double normalized = input[i] / 32768.0;
            double abs = Math.abs(normalized);
            peak = Math.max(peak, abs);
            if (abs >= 0.985) clipped++;
            sumSq += normalized * normalized;
            double window = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1));
            re[i] = normalized * window;
        }

        fft(re, im);

        int half = FFT_SIZE / 2;
        double[] db = new double[half];
        double[] power = new double[half];
        for (int i = 1; i < half; i++) {
            double magnitude = Math.sqrt(re[i] * re[i] + im[i] * im[i]) / (FFT_SIZE / 2.0);
            db[i] = 20.0 * Math.log10(Math.max(magnitude, 1e-9));
            power[i] = magnitude * magnitude;
        }

        double rms = Math.sqrt(sumSq / FFT_SIZE);
        double rmsDb = 20.0 * Math.log10(Math.max(rms, 1e-9));
        double peakDb = 20.0 * Math.log10(Math.max(peak, 1e-9));
        double clippingRatio = clipped / (double) FFT_SIZE;

        int minBin = hzToBin(75);
        int maxBin = Math.min(hzToBin(14000), half - 1);
        int dominantBin = minBin;
        for (int i = minBin + 1; i <= maxBin; i++) {
            if (db[i] > db[dominantBin]) dominantBin = i;
        }
        double dominantHz = binToHz(dominantBin);
        double localSum = 0;
        int localCount = 0;
        for (int i = Math.max(minBin, dominantBin - 14); i <= Math.min(maxBin, dominantBin + 14); i++) {
            if (Math.abs(i - dominantBin) <= 2) continue;
            localSum += db[i];
            localCount++;
        }
        double prominence = db[dominantBin] - (localCount == 0 ? db[dominantBin] : localSum / localCount);

        if (Math.abs(dominantHz - lastPeakHz) < Math.max(24, dominantHz * 0.025)) {
            stablePeakFrames++;
        } else {
            stablePeakFrames = 0;
        }
        lastPeakHz = dominantHz;

        double[] bands = new double[]{
                bandDb(power, 35, 90),
                bandDb(power, 90, 200),
                bandDb(power, 200, 500),
                bandDb(power, 500, 2000),
                bandDb(power, 2000, 5000),
                bandDb(power, 5000, 12000)
        };
        if (!bandsInitialized) {
            System.arraycopy(bands, 0, smoothedBands, 0, bands.length);
            bandsInitialized = true;
        } else {
            for (int i = 0; i < bands.length; i++) {
                smoothedBands[i] = smoothedBands[i] * 0.74 + bands[i] * 0.26;
            }
        }

        double sensitivity = selectedSensitivityIndex == 1 ? -2.0 : selectedSensitivityIndex == 2 ? 2.5 : 0.0;
        int requiredStableFrames = selectedSensitivityIndex == 1 ? 2 : selectedSensitivityIndex == 2 ? 5 : 3;

        double hum50 = bandDb(power, 38, 62);
        double hum100 = bandDb(power, 88, 112);
        double humReference = (bandDb(power, 120, 180) + bandDb(power, 220, 320)) / 2.0;
        boolean humDetected = rmsDb > -58 && hum50 > humReference + 7 + sensitivity && hum100 > humReference + 3 + sensitivity;

        boolean possibleFeedback = rmsDb > -50
                && stablePeakFrames >= requiredStableFrames
                && prominence > 14 + sensitivity
                && db[dominantBin] > -50;
        boolean clipping = clippingRatio > 0.0015 || peakDb > -0.25;
        boolean mud = rmsDb > -58 && smoothedBands[2] > smoothedBands[3] + 6 + sensitivity;
        boolean harsh = rmsDb > -58 && smoothedBands[4] > smoothedBands[3] + 9 + sensitivity;
        boolean hiss = rmsDb > -58 && smoothedBands[5] > smoothedBands[3] + 10 + sensitivity;
        boolean lowHeavy = rmsDb > -58 && (smoothedBands[0] > smoothedBands[3] + 8 + sensitivity
                || smoothedBands[1] > smoothedBands[3] + 9 + sensitivity);
        boolean quiet = rmsDb < -58;

        float[] display = buildDisplay(db);
        String mode = modes[Math.max(0, Math.min(modes.length - 1, selectedModeIndex))];
        double nearestEq = nearestEqFrequency(dominantHz);
        String noteName = frequencyToNote(dominantHz);
        List<String> advice = buildAdvice(mode, rmsDb, clipping, humDetected, possibleFeedback,
                mud, harsh, hiss, lowHeavy, dominantHz, nearestEq, prominence, smoothedBands);

        int score = 100;
        if (clipping) score -= 34;
        if (humDetected) score -= 20;
        if (possibleFeedback) score -= 30;
        if (mud) score -= 10;
        if (harsh) score -= 9;
        if (hiss) score -= 8;
        if (lowHeavy) score -= 7;
        if (quiet) score = Math.min(score, 65);
        score = Math.max(0, Math.min(100, score));

        return new AnalysisResult(rmsDb, peakDb, dominantHz, nearestEq, prominence, noteName, score,
                display, Arrays.copyOf(smoothedBands, smoothedBands.length), advice,
                clipping, humDetected, possibleFeedback, mud, harsh, hiss, lowHeavy, quiet);
    }

    private void resetDisplayStabilization() {
        clippingLatch.reset();
        humLatch.reset();
        feedbackLatch.reset();
        mudLatch.reset();
        harshLatch.reset();
        hissLatch.reset();
        lowHeavyLatch.reset();
        quietLatch.reset();
        displayInitialized = false;
        displayRmsDb = -120;
        displayPeakDb = -120;
        displayDominantHz = 440;
        displayProminence = 0;
        displayScore = 100;
        displayBands = new double[6];
        displaySpectrum = new float[0];
        lastUiPostAt = 0;
        lastAnomalySignature = "";
        lastRecommendationText = "";
    }

    private AnalysisResult stabilizeResult(AnalysisResult raw) {
        if (!displayInitialized) {
            displayRmsDb = raw.rmsDb;
            displayPeakDb = raw.peakDb;
            displayDominantHz = Math.max(35, raw.dominantHz);
            displayProminence = raw.prominence;
            displayScore = raw.score;
            displayBands = Arrays.copyOf(raw.bands, raw.bands.length);
            displaySpectrum = Arrays.copyOf(raw.display, raw.display.length);
            displayInitialized = true;
        } else {
            displayRmsDb += (raw.rmsDb - displayRmsDb) * METRIC_ALPHA;
            double peakAlpha = raw.peakDb > displayPeakDb ? PEAK_ATTACK_ALPHA : PEAK_RELEASE_ALPHA;
            displayPeakDb += (raw.peakDb - displayPeakDb) * peakAlpha;
            displayScore += (raw.score - displayScore) * METRIC_ALPHA;
            displayProminence += (raw.prominence - displayProminence) * METRIC_ALPHA;

            // Dominantna frekvenca se spremeni samo ob dovolj jasnem vrhu.
            // Logaritemsko glajenje je bolj naravno za frekvenčno skalo.
            if (raw.prominence >= 8 && stablePeakFrames >= 3 && raw.dominantHz >= 35) {
                double oldLog = Math.log(Math.max(35, displayDominantHz));
                double newLog = Math.log(raw.dominantHz);
                displayDominantHz = Math.exp(oldLog + (newLog - oldLog) * DOMINANT_ALPHA);
            }

            if (displayBands.length != raw.bands.length) {
                displayBands = Arrays.copyOf(raw.bands, raw.bands.length);
            } else {
                for (int i = 0; i < displayBands.length; i++) {
                    displayBands[i] += (raw.bands[i] - displayBands[i]) * METRIC_ALPHA;
                }
            }

            if (displaySpectrum.length != raw.display.length) {
                displaySpectrum = Arrays.copyOf(raw.display, raw.display.length);
            } else {
                for (int i = 0; i < displaySpectrum.length; i++) {
                    displaySpectrum[i] += (raw.display[i] - displaySpectrum[i]) * 0.13f;
                }
            }
        }

        boolean clipping = clippingLatch.update(raw.clipping);
        boolean hum = humLatch.update(raw.hum);
        boolean feedback = feedbackLatch.update(raw.feedback);
        boolean mud = mudLatch.update(raw.mud);
        boolean harsh = harshLatch.update(raw.harsh);
        boolean hiss = hissLatch.update(raw.hiss);
        boolean lowHeavy = lowHeavyLatch.update(raw.lowHeavy);
        boolean quiet = quietLatch.update(raw.quiet);

        int stableScore = (int) Math.round(displayScore / 2.0) * 2;
        stableScore = Math.max(0, Math.min(100, stableScore));
        double nearestEq = nearestEqFrequency(displayDominantHz);
        String noteName = frequencyToNote(displayDominantHz);
        String mode = modes[Math.max(0, Math.min(modes.length - 1, selectedModeIndex))];
        List<String> advice = buildAdvice(mode, displayRmsDb, clipping, hum, feedback,
                mud, harsh, hiss, lowHeavy, displayDominantHz, nearestEq,
                (feedback ? displayProminence : Math.min(displayProminence, 9.9)), displayBands);

        return new AnalysisResult(displayRmsDb, displayPeakDb, displayDominantHz, nearestEq,
                displayProminence, noteName, stableScore,
                Arrays.copyOf(displaySpectrum, displaySpectrum.length),
                Arrays.copyOf(displayBands, displayBands.length), advice,
                clipping, hum, feedback, mud, harsh, hiss, lowHeavy, quiet);
    }

    private List<String> buildAdvice(String mode, double rmsDb, boolean clipping, boolean hum,
                                     boolean feedback, boolean mud, boolean harsh, boolean hiss,
                                     boolean lowHeavy, double dominantHz, double nearestEq,
                                     double prominence, double[] b) {
        List<String> items = new ArrayList<>();

        if (rmsDb < -58) {
            items.add("Signal je zelo tih. Pred diagnozo preveri, ali je bend dovolj glasen in ali mikrofon telefona ni prekrit.");
            return items;
        }

        if (clipping) {
            items.add("Zaznano je možno digitalno popačenje. Na sumljivem kanalu vključi PFL/Solo, nato znižaj vhodni GAIN. Fader naj za zdaj ostane na običajnem položaju.");
        }
        if (feedback) {
            items.add(String.format(Locale.US,
                    "Možna mikrofonija je okoli %.0f Hz. Na PEQ izberi najbližje območje %s, nastavi Q približno 8–12 in začni z rezom −3 dB. Najprej za 2–3 dB znižaj problematični monitor ter utihni mikrofone enega za drugim.",
                    dominantHz, formatFrequency(nearestEq)));
        }
        if (hum) {
            items.add("Prisoten je vzorec 50/100 Hz brnenja. Preveri DI-box, napajalnike, kable in ground-lift. EQ uporabi šele po preverjanju povezav.");
        }

        if (lowHeavy) {
            if (mode.equals("Bas kitara") || mode.equals("Kick") || mode.equals("Celotni bobni") || mode.equals("Celotni bend")) {
                items.add("Nizki del je zelo močan. Preveri razmerje kick/bas in na vseh virih, ki ne potrebujejo globokega basa, vključi HPF. Ne reži najprej celotnega master basa.");
            } else {
                items.add("Na tem viru je veliko nizkih frekvenc. Vključi HPF in ga počasi dviguj, dokler zvok ostane naraven.");
            }
        }
        if (mud) {
            items.add("Območje 200–500 Hz deluje zgoščeno. Na problematičnem kanalu poskusi zmanjšati 2–3 dB s srednje širokim Q in primerjaj z bypassom.");
        }
        if (harsh) {
            items.add("Območje 2–5 kHz je izrazito. Če zvok reže ali je vokal naporen, rahlo zmanjšaš presence; pred tem preveri, ali vir ni samo preglasen.");
        }
        if (hiss) {
            items.add("Visokofrekvenčnega šuma je veliko. Preveri gain, odprte mikrofone in kompresorjev make-up gain. Po potrebi zapri nepotrebne kanale ali uporabi nežen low-pass.");
        }

        if (mode.equals("Vokal") || mode.equals("Govor")) {
            if (b[3] < b[2] + 1 && b[4] < b[2] + 3) {
                items.add("Vokal je lahko slabše razumljiv. Najprej zmanjšaš 250–400 Hz, nato po potrebi zelo nežno poudariš 2–4 kHz.");
            }
        } else if (mode.equals("Bas kitara")) {
            if (b[1] < b[2] - 3) {
                items.add("Bas ima več sredine kot temelja. Preveri DI/amp signal in po potrebi nežno podpri 80–120 Hz; ničesar ne dodajaj, dokler gain ni pravilno nastavljen.");
            }
        } else if (mode.equals("Kick")) {
            items.add("Pri kicku loči težo in udarec: preveri 60–100 Hz za telo ter 2–4 kHz za napad. Najprej odstrani nepotrebno sredino, ne dodajaj obeh območij hkrati.");
        } else if (mode.equals("Snare")) {
            items.add("Pri snaru preveri telo okoli 180–250 Hz in napad okoli 2–5 kHz. Če obroči, poišči ozek resonančni vrh in ga zmanjšaj z ozkim Q.");
        } else if (mode.equals("Monitor")) {
            items.add("Pri monitorju najprej zmanjšaš pošiljanje problematičnega mikrofona. EQ monitorja uporabi za ozke resonančne vrhove, ne za popravljanje tona celotnega benda.");
        }

        if (referenceBands != null) {
            String comparison = buildReferenceComparison(rmsDb, b);
            if (!comparison.isEmpty()) items.add(comparison);
        }

        if (items.isEmpty()) {
            items.add("Ni zaznane izrazite anomalije. Nadaljuj po kanalih: gain s PFL, HPF, osnovni EQ, kompresija in šele nato monitorji ter efekti.");
        }
        if (prominence > 10 && !feedback) {
            items.add(String.format(Locale.US,
                    "Najmočnejši trenutni vrh je okoli %.0f Hz (%s), vendar še ni dovolj stabilen za opozorilo na mikrofonijo.",
                    dominantHz, formatFrequency(nearestEq)));
        }
        return items;
    }

    private String buildReferenceComparison(double rmsDb, double[] bands) {
        double rmsDelta = rmsDb - referenceRmsDb;
        int biggestIndex = 0;
        double biggestDelta = 0;
        for (int i = 0; i < bands.length; i++) {
            double delta = bands[i] - referenceBands[i];
            if (Math.abs(delta) > Math.abs(biggestDelta)) {
                biggestDelta = delta;
                biggestIndex = i;
            }
        }
        if (Math.abs(rmsDelta) < 1.5 && Math.abs(biggestDelta) < 2.5) {
            return "Primerjava A/B: zvok je zelo podoben shranjeni referenci A.";
        }
        String[] names = {"35–90 Hz", "90–200 Hz", "200–500 Hz", "500 Hz–2 kHz", "2–5 kHz", "5–12 kHz"};
        String direction = biggestDelta > 0 ? "več" : "manj";
        return String.format(Locale.US,
                "Primerjava A/B: skupna raven je %+,.1f dB glede na referenco; največja sprememba je %s energije v območju %s.",
                rmsDelta, direction, names[biggestIndex]);
    }

    private float[] buildDisplay(double[] db) {
        int bars = 64;
        float[] out = new float[bars];
        double minHz = 35;
        double maxHz = 16000;
        for (int i = 0; i < bars; i++) {
            double f1 = minHz * Math.pow(maxHz / minHz, i / (double) bars);
            double f2 = minHz * Math.pow(maxHz / minHz, (i + 1) / (double) bars);
            int start = Math.max(1, hzToBin(f1));
            int end = Math.min(db.length - 1, Math.max(start, hzToBin(f2)));
            double max = -120;
            for (int j = start; j <= end; j++) max = Math.max(max, db[j]);
            out[i] = (float) Math.max(0, Math.min(1, (max + 85) / 75.0));
        }
        return out;
    }

    private double bandDb(double[] power, double lowHz, double highHz) {
        int start = Math.max(1, hzToBin(lowHz));
        int end = Math.min(power.length - 1, hzToBin(highHz));
        double sum = 0;
        for (int i = start; i <= end; i++) sum += power[i];
        return 10.0 * Math.log10(Math.max(sum, 1e-12));
    }

    private int hzToBin(double hz) {
        return (int) Math.round(hz * FFT_SIZE / SAMPLE_RATE);
    }

    private double binToHz(int bin) {
        return bin * SAMPLE_RATE / (double) FFT_SIZE;
    }

    private double nearestEqFrequency(double frequency) {
        double nearest = EQ_FREQUENCIES[0];
        double bestDistance = Double.MAX_VALUE;
        for (double candidate : EQ_FREQUENCIES) {
            double distance = Math.abs(Math.log(frequency / candidate));
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = candidate;
            }
        }
        return nearest;
    }

    private String frequencyToNote(double frequency) {
        if (frequency <= 0) return "—";
        double exactMidi = 69.0 + 12.0 * Math.log(frequency / 440.0) / Math.log(2.0);
        int midi = (int) Math.round(exactMidi);
        int cents = (int) Math.round((exactMidi - midi) * 100.0);
        String[] noteNames = {"C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"};
        int noteIndex = ((midi % 12) + 12) % 12;
        int octave = midi / 12 - 1;
        return noteNames[noteIndex] + octave + String.format(Locale.US, " %+dc", cents);
    }

    private String formatFrequency(double frequency) {
        if (frequency >= 1000) {
            double value = frequency / 1000.0;
            if (Math.abs(value - Math.round(value)) < 0.01) {
                return String.format(Locale.US, "%.0f kHz", value);
            }
            return String.format(Locale.US, "%.2g kHz", value);
        }
        return String.format(Locale.US, "%.0f Hz", frequency);
    }

    private static void fft(double[] real, double[] imag) {
        int n = real.length;
        int j = 0;
        for (int i = 1; i < n; i++) {
            int bit = n >> 1;
            while ((j & bit) != 0) {
                j ^= bit;
                bit >>= 1;
            }
            j ^= bit;
            if (i < j) {
                double tmp = real[i]; real[i] = real[j]; real[j] = tmp;
                tmp = imag[i]; imag[i] = imag[j]; imag[j] = tmp;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2 * Math.PI / len;
            double wLenR = Math.cos(angle);
            double wLenI = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double wR = 1;
                double wI = 0;
                for (int k = 0; k < len / 2; k++) {
                    int even = i + k;
                    int odd = even + len / 2;
                    double oddR = real[odd] * wR - imag[odd] * wI;
                    double oddI = real[odd] * wI + imag[odd] * wR;
                    real[odd] = real[even] - oddR;
                    imag[odd] = imag[even] - oddI;
                    real[even] += oddR;
                    imag[even] += oddI;
                    double nextWR = wR * wLenR - wI * wLenI;
                    wI = wR * wLenI + wI * wLenR;
                    wR = nextWR;
                }
            }
        }
    }

    private void renderResult(AnalysisResult result) {
        if (!running) return;
        latestResult = result;
        renderedFrames++;
        sessionMinScore = Math.min(sessionMinScore, result.score);
        sessionMaxPeakDb = Math.max(sessionMaxPeakDb, result.peakDb);
        if (result.clipping) sessionClipFrames++;
        if (result.hum) sessionHumFrames++;
        if (result.feedback) sessionFeedbackFrames++;
        if (result.mud) sessionMudFrames++;
        if (result.harsh) sessionHarshFrames++;
        if (result.hiss) sessionHissFrames++;

        rmsText.setText(String.format(Locale.US, "%.1f dBFS", result.rmsDb));
        peakText.setText(String.format(Locale.US, "%.1f dBFS", result.peakDb));
        dominantText.setText(result.dominantHz >= 1000
                ? String.format(Locale.US, "%.2f kHz", result.dominantHz / 1000.0)
                : String.format(Locale.US, "%.0f Hz", result.dominantHz));
        noteText.setText(result.noteName + " · " + formatFrequency(result.nearestEq));
        scoreText.setText(String.valueOf(result.score));
        long now = SystemClock.elapsedRealtime();
        if (trendView != null && (lastTrendPointAt == 0 || now - lastTrendPointAt >= TREND_INTERVAL_MS)) {
            trendView.addPoint(result.score, result.peakDb, result.clipping || result.feedback);
            lastTrendPointAt = now;
        }
        timerText.setText(formatDuration((now - sessionStartedAt) / 1000));
        renderAnomalies(result);
        String issue = primaryIssue(result);
        String action = result.advice.isEmpty()
                ? "Nadaljuj po kanalih in preverjaj po eno spremembo."
                : result.advice.get(0);
        if (!issue.contentEquals(primaryIssueText.getText())) primaryIssueText.setText(issue);
        if (!action.contentEquals(primaryActionText.getText())) primaryActionText.setText(action);

        int statusColor;
        String status;
        if (result.clipping || result.feedback) {
            status = "Potrebna pozornost";
            statusColor = DANGER;
        } else if (result.hum || result.score < 82) {
            status = "Preveri priporočila";
            statusColor = WARNING;
        } else {
            status = "Zvok je stabilen";
            statusColor = ACCENT;
        }
        stateText.setText(status);
        stateText.setTextColor(statusColor);
        scoreText.setTextColor(statusColor);

        StringBuilder text = new StringBuilder();
        int maxSteps = Math.min(3, result.advice.size());
        for (int i = 0; i < maxSteps; i++) {
            if (i > 0) text.append("\n\n");
            text.append(i + 1).append(". ").append(result.advice.get(i));
        }
        String recommendation = text.toString();
        if (!recommendation.equals(lastRecommendationText)) {
            recommendationText.setText(recommendation);
            lastRecommendationText = recommendation;
        }
    }

    private void renderAnomalies(AnalysisResult result) {
        String signature;
        if (result == null) {
            signature = "WAIT";
        } else {
            signature = (result.clipping ? "C" : "-")
                    + (result.feedback ? "F" : "-")
                    + (result.hum ? "H" : "-")
                    + (result.lowHeavy ? "L" : "-")
                    + (result.mud ? "M" : "-")
                    + (result.harsh ? "A" : "-")
                    + (result.hiss ? "S" : "-")
                    + (result.quiet ? "Q" : "-");
        }
        if (signature.equals(lastAnomalySignature)) return;
        lastAnomalySignature = signature;
        anomalyContainer.removeAllViews();
        if (result == null) {
            addChip("ANALIZA SE UMIRJA", MUTED);
            return;
        }
        boolean any = false;
        if (result.clipping) { addChip("CLIPPING", DANGER); any = true; }
        if (result.feedback) { addChip("MIKROFONIJA", DANGER); any = true; }
        if (result.hum) { addChip("50/100 Hz HUM", WARNING); any = true; }
        if (result.lowHeavy) { addChip("PREVEČ NIZKIH", WARNING); any = true; }
        if (result.mud) { addChip("MOTNO 200–500 Hz", WARNING); any = true; }
        if (result.harsh) { addChip("OSTRO 2–5 kHz", WARNING); any = true; }
        if (result.hiss) { addChip("ŠUM 5–12 kHz", WARNING); any = true; }
        if (result.quiet) { addChip("PRETIH SIGNAL", MUTED); any = true; }
        if (!any) addChip("BREZ IZRAZITIH TEŽAV", ACCENT);
    }

    private void addChip(String text, int color) {
        TextView chip = label(text, 11, color, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable background = rounded(CARD_3, 14);
        background.setStroke(dp(1), color);
        chip.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        if (anomalyContainer.getChildCount() > 0) params.leftMargin = dp(8);
        anomalyContainer.addView(chip, params);
    }

    private String primaryIssue(AnalysisResult result) {
        if (result.clipping) return "Clipping oziroma premočan vhodni signal";
        if (result.feedback) return "Možna mikrofonija okoli " + formatFrequency(result.nearestEq);
        if (result.hum) return "Brnenje električnega omrežja pri 50/100 Hz";
        if (result.mud) return "Motnost v območju 200–500 Hz";
        if (result.harsh) return "Ostrina v območju 2–5 kHz";
        if (result.hiss) return "Preveč visokofrekvenčnega šuma";
        if (result.lowHeavy) return "Preveč energije v nizkih frekvencah";
        if (result.quiet) return "Signal je prešibek za zanesljivo diagnozo";
        return "Brez izrazite težave – zvok je trenutno stabilen";
    }

    private void compareNow() {
        if (!running || latestResult == null || referenceBands == null) {
            Toast.makeText(this, "Najprej shrani meritev PREJ.", Toast.LENGTH_SHORT).show();
            return;
        }
        String comparison = buildReferenceComparison(latestResult.rmsDb, latestResult.bands);
        int delta = latestResult.score - referenceScore;
        String verdict;
        if (delta >= 4) verdict = "Rezultat je boljši za " + delta + " točk.";
        else if (delta <= -4) verdict = "Rezultat je slabši za " + Math.abs(delta) + " točk. Razveljavi zadnjo spremembo ali jo omili.";
        else verdict = "Skupna ocena je skoraj enaka.";
        comparisonText.setText("PREJ " + referenceScore + "/100 → ZDAJ " + latestResult.score + "/100\n"
                + verdict + "\n" + comparison);
    }

    private void showGuideDialog() {
        updateGuide(modeSpinner == null ? 0 : modeSpinner.getSelectedItemPosition());
        TextView content = label(guideText.getText().toString(), 15, TEXT, false);
        content.setLineSpacing(dp(4), 1f);
        content.setPadding(dp(20), dp(12), dp(20), dp(10));
        new AlertDialog.Builder(this)
                .setTitle("Hiter vodič – " + modes[Math.max(0, modeSpinner.getSelectedItemPosition())])
                .setView(content)
                .setPositiveButton("Zapri", null)
                .show();
    }

    private void showHistoryDialog() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(12), dp(16), dp(12));
        scroll.addView(list);
        List<SessionRecord> records = loadHistory();
        if (records.isEmpty()) {
            list.addView(label("Zgodovina je prazna. Zaključi prvo tonsko vajo.", 14, MUTED, false), matchWrap());
        } else {
            for (int i = 0; i < Math.min(12, records.size()); i++) {
                SessionRecord record = records.get(i);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(12), dp(11), dp(12), dp(11));
                row.setBackground(rounded(CARD_2, 14));
                row.addView(label(record.mode + " · " + record.timestamp, 13, TEXT, true), matchWrap());
                TextView details = label(formatDuration(record.durationSec) + " · min " + record.minScore
                        + "/100 · peak " + String.format(Locale.US, "%.1f dBFS", record.maxPeakDb)
                        + "\n" + record.anomalies, 12, MUTED, false);
                details.setLineSpacing(dp(2), 1f);
                LinearLayout.LayoutParams detailsParams = matchWrap();
                detailsParams.topMargin = dp(5);
                row.addView(details, detailsParams);
                LinearLayout.LayoutParams rowParams = matchWrap();
                if (i > 0) rowParams.topMargin = dp(8);
                list.addView(row, rowParams);
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Zgodovina tonskih sej")
                .setView(scroll)
                .setNegativeButton("Počisti", (dialog, which) -> confirmClearHistory())
                .setPositiveButton("Zapri", null)
                .show();
    }

    private void saveReference() {
        if (!running || latestResult == null) {
            Toast.makeText(this, "Najprej počakaj na meritev zvoka.", Toast.LENGTH_SHORT).show();
            return;
        }
        referenceBands = Arrays.copyOf(latestResult.bands, latestResult.bands.length);
        referenceRmsDb = latestResult.rmsDb;
        referenceScore = latestResult.score;
        referenceButton.setText("POSODOBI PREJ");
        compareButton.setEnabled(true);
        compareButton.setAlpha(1f);
        comparisonText.setText("Meritev PREJ je shranjena. Zdaj spremeni eno nastavitev na mešalni mizi in pritisni PRIMERJAJ ZDAJ.");
        Toast.makeText(this, "Meritev PREJ je shranjena.", Toast.LENGTH_SHORT).show();
    }

    private void stopAnalysis(boolean saveSession) {
        if (!running) return;
        running = false;
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) { }
            recorder.release();
            recorder = null;
        }
        if (audioThread != null) {
            try { audioThread.join(300); } catch (InterruptedException ignored) { }
            audioThread = null;
        }

        modeSpinner.setEnabled(true);
        sensitivitySpinner.setEnabled(true);
        startButton.setText("ZAČNI NOVO ANALIZO");
        startButton.setTextColor(BG);
        startButton.setBackground(rounded(ACCENT, 16));
        referenceButton.setEnabled(false);
        referenceButton.setAlpha(0.55f);
        compareButton.setEnabled(false);
        compareButton.setAlpha(0.55f);
        stateText.setText("Analiza ustavljena");
        stateText.setTextColor(TEXT);

        long durationSec = Math.max(0, (SystemClock.elapsedRealtime() - sessionStartedAt) / 1000);
        timerText.setText(formatDuration(durationSec));
        if (saveSession && latestResult != null && durationSec >= 2) {
            latestSession = createSessionRecord(durationSec);
            saveSession(latestSession);
            renderLatestSession(latestSession);
            renderHistory();
            shareButton.setEnabled(true);
            shareButton.setAlpha(1f);
        } else if (saveSession) {
            sessionText.setText("Seja je bila prekratka za uporaben povzetek.");
        }
    }

    private SessionRecord createSessionRecord(long durationSec) {
        int denominator = Math.max(1, renderedFrames);
        int clipPercent = sessionClipFrames * 100 / denominator;
        int humPercent = sessionHumFrames * 100 / denominator;
        int feedbackPercent = sessionFeedbackFrames * 100 / denominator;
        int mudPercent = sessionMudFrames * 100 / denominator;
        int harshPercent = sessionHarshFrames * 100 / denominator;
        int hissPercent = sessionHissFrames * 100 / denominator;

        List<String> anomalies = new ArrayList<>();
        if (clipPercent >= 3) anomalies.add("clipping " + clipPercent + "%");
        if (feedbackPercent >= 3) anomalies.add("mikrofonija " + feedbackPercent + "%");
        if (humPercent >= 5) anomalies.add("hum " + humPercent + "%");
        if (mudPercent >= 10) anomalies.add("motnost " + mudPercent + "%");
        if (harshPercent >= 10) anomalies.add("ostrina " + harshPercent + "%");
        if (hissPercent >= 10) anomalies.add("šum " + hissPercent + "%");
        String anomalySummary = anomalies.isEmpty() ? "brez trajnih anomalij" : join(anomalies, ", ");

        String timestamp = new SimpleDateFormat("dd. MM. yyyy, HH:mm", new Locale("sl", "SI")).format(new Date());
        return new SessionRecord(timestamp,
                modes[Math.max(0, Math.min(modes.length - 1, selectedModeIndex))],
                durationSec, sessionMinScore, sessionMaxPeakDb, anomalySummary);
    }

    private void renderLatestSession(SessionRecord record) {
        sessionText.setText(
                record.mode + " · " + formatDuration(record.durationSec)
                        + "\nNajnižja ocena: " + record.minScore + "/100"
                        + "\nNajvečji peak: " + String.format(Locale.US, "%.1f dBFS", record.maxPeakDb)
                        + "\nZaznano: " + record.anomalies);
    }

    private void shareLatestSession() {
        if (latestSession == null) return;
        String text = "SoundCheck Assistant v0.3.1 – povzetek tonske seje\n"
                + latestSession.timestamp + "\n"
                + "Vir: " + latestSession.mode + "\n"
                + "Trajanje: " + formatDuration(latestSession.durationSec) + "\n"
                + "Najnižja ocena: " + latestSession.minScore + "/100\n"
                + "Največji peak: " + String.format(Locale.US, "%.1f dBFS", latestSession.maxPeakDb) + "\n"
                + "Zaznano: " + latestSession.anomalies;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "Povzetek tonske seje");
        intent.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(intent, "Deli povzetek"));
    }

    private void saveSession(SessionRecord record) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            JSONArray existing = new JSONArray(prefs.getString(KEY_HISTORY, "[]"));
            JSONArray updated = new JSONArray();
            updated.put(record.toJson());
            for (int i = 0; i < existing.length() && updated.length() < MAX_HISTORY; i++) {
                updated.put(existing.getJSONObject(i));
            }
            prefs.edit().putString(KEY_HISTORY, updated.toString()).apply();
        } catch (Exception ignored) { }
    }

    private List<SessionRecord> loadHistory() {
        List<SessionRecord> records = new ArrayList<>();
        try {
            String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_HISTORY, "[]");
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                records.add(SessionRecord.fromJson(array.getJSONObject(i)));
            }
        } catch (Exception ignored) { }
        return records;
    }

    private void renderHistory() {
        if (historyContainer == null) return;
        historyContainer.removeAllViews();
        List<SessionRecord> records = loadHistory();
        if (records.isEmpty()) {
            TextView empty = label("Zgodovina je prazna. Zaključi prvo analizo.", 13, MUTED, false);
            empty.setPadding(0, dp(8), 0, dp(8));
            historyContainer.addView(empty, matchWrap());
            return;
        }
        int shown = Math.min(6, records.size());
        for (int i = 0; i < shown; i++) {
            SessionRecord record = records.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(11), dp(12), dp(11));
            row.setBackground(rounded(CARD_2, 14));

            TextView first = label(record.mode + " · " + record.timestamp, 13, TEXT, true);
            row.addView(first, matchWrap());
            TextView second = label(
                    formatDuration(record.durationSec) + " · min " + record.minScore
                            + "/100 · peak " + String.format(Locale.US, "%.1f dBFS", record.maxPeakDb),
                    12, MUTED, false);
            LinearLayout.LayoutParams secondParams = matchWrap();
            secondParams.topMargin = dp(4);
            row.addView(second, secondParams);
            TextView third = label(record.anomalies, 12,
                    record.anomalies.equals("brez trajnih anomalij") ? ACCENT : WARNING, false);
            LinearLayout.LayoutParams thirdParams = matchWrap();
            thirdParams.topMargin = dp(4);
            row.addView(third, thirdParams);

            LinearLayout.LayoutParams rowParams = matchWrap();
            if (i > 0) rowParams.topMargin = dp(8);
            historyContainer.addView(row, rowParams);
        }
    }

    private void confirmClearHistory() {
        List<SessionRecord> records = loadHistory();
        if (records.isEmpty()) {
            Toast.makeText(this, "Zgodovina je že prazna.", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Počistim zgodovino?")
                .setMessage("Izbrisani bodo vsi lokalno shranjeni povzetki tonskih sej.")
                .setNegativeButton("Prekliči", null)
                .setPositiveButton("Počisti", (dialog, which) -> {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_HISTORY).apply();
                    renderHistory();
                })
                .show();
    }

    private void updateGuide(int modeIndex) {
        if (guideText == null) return;
        String mode = modes[Math.max(0, Math.min(modes.length - 1, modeIndex))];
        String guide;
        switch (mode) {
            case "Vokal":
            case "Govor":
                guide = "1. PFL/Solo in nastavi gain z dovolj rezerve.\n2. Vključi HPF in ga dviguj do naravne meje.\n3. Odstrani motnost okoli 250–400 Hz.\n4. Prisotnost 2–4 kHz dodaj samo po potrebi.\n5. Kompresor in monitor nastavi šele po osnovnem tonu.";
                break;
            case "Bas kitara":
                guide = "1. Primerjaj DI in amp signal.\n2. Gain nastavi brez clippinga.\n3. Uredi razmerje 80–120 Hz in 500–900 Hz.\n4. Z kickom določi, kdo nosi najgloblji bas.\n5. Kompresijo dodaj nežno in preveri attack.";
                break;
            case "Električna kitara":
            case "Akustična kitara":
                guide = "1. Najprej uredi zvok na instrumentu/ampu.\n2. Nastavi gain in vključi HPF.\n3. Poišči motnost 180–350 Hz.\n4. Ostrino 2–4 kHz zmanjšaj, ne prekrivaj vokala.\n5. Efekte dodaj po monitorjih.";
                break;
            case "Kick":
                guide = "1. Preveri postavitev in polariteto mikrofona.\n2. Nastavi gain brez clippinga.\n3. Izberi telo 60–100 Hz.\n4. Odstrani kartonski ton 250–500 Hz.\n5. Napad 2–4 kHz dodaj samo toliko, da se kick prebije.";
                break;
            case "Snare":
                guide = "1. Preveri zgornji/spodnji mikrofon in polariteto.\n2. Nastavi gain.\n3. Uredi telo okoli 180–250 Hz.\n4. Poišči in ozko odreži moteče obroče.\n5. Gate uporabi previdno, da ne odreže dinamike.";
                break;
            case "Celotni bobni":
                guide = "1. Začni z overheadoma in preveri polariteto.\n2. Dodaj kick in snare.\n3. Nato tomi in hi-hat.\n4. HPF uporabi na virih brez globokega basa.\n5. Šele nato skupinska kompresija in reverb.";
                break;
            case "Klaviature":
                guide = "1. Preveri stereo povezavo in DI.\n2. Nastavi gain ob najglasnejšem programu.\n3. S HPF naredi prostor basu in kicku.\n4. Zmanjšaj območje, ki prekriva vokal.\n5. Preveri vse programe, ne samo enega.";
                break;
            case "Monitor":
                guide = "1. Začni z vsemi sendi nizko.\n2. Dodajaj samo tisto, kar izvajalec potrebuje.\n3. Ring-out izvajaj z ozkimi PEQ rezi.\n4. Problematični mikrofon najprej zmanjšaš v monitorju.\n5. Končno stanje preveri z odprtimi vsemi mikrofoni.";
                break;
            default:
                guide = "1. Vsi faderji dol, gain nastavi s PFL.\n2. Po kanalih uredi HPF in osnovni EQ.\n3. Zgradi ritem: kick, bas, bobni.\n4. Dodaj kitare, klaviature in vokale.\n5. Monitorji, kompresija in efekti pridejo na koncu.";
                break;
        }
        guideText.setText(guide);
    }

    private String formatDuration(long seconds) {
        long minutes = seconds / 60;
        long remaining = seconds % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, remaining);
    }

    private String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) builder.append(separator);
            builder.append(values.get(i));
        }
        return builder.toString();
    }

    private void showAudioError(String message) {
        stateText.setText("Napaka mikrofona");
        stateText.setTextColor(DANGER);
        recommendationText.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (running) stopAnalysis(true);
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));
        layout.setBackground(rounded(CARD, 18));
        return layout;
    }

    private TextView label(String value, int sp, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        text.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return text;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchHeight(int height) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private class DarkAdapter extends ArrayAdapter<String> {
        DarkAdapter(Context context, String[] values) {
            super(context, android.R.layout.simple_spinner_item, values);
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView view = (TextView) super.getView(position, convertView, parent);
            view.setTextColor(TEXT);
            view.setTextSize(15);
            view.setPadding(dp(14), 0, dp(14), 0);
            view.setBackground(rounded(CARD_2, 14));
            return view;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            TextView view = (TextView) super.getDropDownView(position, convertView, parent);
            view.setTextColor(TEXT);
            view.setTextSize(15);
            view.setPadding(dp(16), dp(14), dp(16), dp(14));
            view.setBackgroundColor(CARD_2);
            return view;
        }
    }

    private static class EvidenceLatch {
        private final int attackThreshold;
        private final int releaseThreshold;
        private final int riseStep;
        private final int fallStep;
        private int evidence = 0;
        private boolean active = false;

        EvidenceLatch(int attackThreshold, int releaseThreshold, int riseStep, int fallStep) {
            this.attackThreshold = attackThreshold;
            this.releaseThreshold = releaseThreshold;
            this.riseStep = riseStep;
            this.fallStep = fallStep;
        }

        boolean update(boolean detected) {
            if (detected) {
                evidence = Math.min(attackThreshold + 6, evidence + riseStep);
            } else {
                evidence = Math.max(0, evidence - fallStep);
            }
            if (!active && evidence >= attackThreshold) active = true;
            if (active && evidence <= releaseThreshold) active = false;
            return active;
        }

        void reset() {
            evidence = 0;
            active = false;
        }
    }

    private static class AnalysisResult {
        final double rmsDb;
        final double peakDb;
        final double dominantHz;
        final double nearestEq;
        final double prominence;
        final String noteName;
        final int score;
        final float[] display;
        final double[] bands;
        final List<String> advice;
        final boolean clipping;
        final boolean hum;
        final boolean feedback;
        final boolean mud;
        final boolean harsh;
        final boolean hiss;
        final boolean lowHeavy;
        final boolean quiet;

        AnalysisResult(double rmsDb, double peakDb, double dominantHz, double nearestEq,
                       double prominence, String noteName, int score, float[] display, double[] bands,
                       List<String> advice, boolean clipping, boolean hum, boolean feedback,
                       boolean mud, boolean harsh, boolean hiss, boolean lowHeavy, boolean quiet) {
            this.rmsDb = rmsDb;
            this.peakDb = peakDb;
            this.dominantHz = dominantHz;
            this.nearestEq = nearestEq;
            this.prominence = prominence;
            this.noteName = noteName;
            this.score = score;
            this.display = display;
            this.bands = bands;
            this.advice = advice;
            this.clipping = clipping;
            this.hum = hum;
            this.feedback = feedback;
            this.mud = mud;
            this.harsh = harsh;
            this.hiss = hiss;
            this.lowHeavy = lowHeavy;
            this.quiet = quiet;
        }
    }

    private static class SessionRecord {
        final String timestamp;
        final String mode;
        final long durationSec;
        final int minScore;
        final double maxPeakDb;
        final String anomalies;

        SessionRecord(String timestamp, String mode, long durationSec,
                      int minScore, double maxPeakDb, String anomalies) {
            this.timestamp = timestamp;
            this.mode = mode;
            this.durationSec = durationSec;
            this.minScore = minScore;
            this.maxPeakDb = maxPeakDb;
            this.anomalies = anomalies;
        }

        JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("timestamp", timestamp);
            json.put("mode", mode);
            json.put("durationSec", durationSec);
            json.put("minScore", minScore);
            json.put("maxPeakDb", maxPeakDb);
            json.put("anomalies", anomalies);
            return json;
        }

        static SessionRecord fromJson(JSONObject json) {
            return new SessionRecord(
                    json.optString("timestamp", ""),
                    json.optString("mode", "Celotni bend"),
                    json.optLong("durationSec", 0),
                    json.optInt("minScore", 0),
                    json.optDouble("maxPeakDb", -120),
                    json.optString("anomalies", "brez podatkov"));
        }
    }

    private static class TrendView extends View {
        private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint scorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint peakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dangerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path scorePath = new Path();
        private final Path peakPath = new Path();
        private final List<Float> scores = new ArrayList<>();
        private final List<Float> peaks = new ArrayList<>();
        private final List<Boolean> dangers = new ArrayList<>();
        private static final int MAX_POINTS = 60;
        private float animatedLastScore = 0f;
        private float animatedLastPeak = 0f;

        TrendView(Context context) {
            super(context);
            float density = getResources().getDisplayMetrics().density;
            gridPaint.setColor(Color.rgb(62, 72, 88));
            gridPaint.setStrokeWidth(density);
            scorePaint.setColor(ACCENT);
            scorePaint.setStyle(Paint.Style.STROKE);
            scorePaint.setStrokeWidth(2.2f * density);
            scorePaint.setStrokeCap(Paint.Cap.ROUND);
            peakPaint.setColor(INFO);
            peakPaint.setStyle(Paint.Style.STROKE);
            peakPaint.setStrokeWidth(1.8f * density);
            dangerPaint.setColor(DANGER);
            textPaint.setColor(Color.rgb(150, 162, 178));
            textPaint.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
        }

        void clear() {
            scores.clear(); peaks.clear(); dangers.clear();
            animatedLastScore = 0f;
            animatedLastPeak = 0f;
            invalidate();
        }

        void addPoint(int score, double peakDb, boolean danger) {
            float newPeak = (float) Math.max(0, Math.min(100, (peakDb + 60.0) * 100.0 / 60.0));
            if (scores.size() >= MAX_POINTS) {
                scores.remove(0); peaks.remove(0); dangers.remove(0);
            }
            if (scores.isEmpty()) {
                animatedLastScore = score;
                animatedLastPeak = newPeak;
            } else {
                animatedLastScore = scores.get(scores.size() - 1);
                animatedLastPeak = peaks.get(peaks.size() - 1);
            }
            scores.add((float) score);
            peaks.add(newPeak);
            dangers.add(danger);
            postInvalidateOnAnimation();
        }

        private void buildSmoothPath(Path path, List<Float> values, float animatedLast,
                                     float left, float bottom, float step, float offset,
                                     float height) {
            path.reset();
            if (values.isEmpty()) return;
            int count = values.size();
            float firstValue = count == 1 ? animatedLast : values.get(0);
            float firstX = left + offset;
            float firstY = bottom - firstValue / 100f * height;
            path.moveTo(firstX, firstY);
            for (int i = 1; i < count; i++) {
                float previousValue = i - 1 == count - 1 ? animatedLast : values.get(i - 1);
                float currentValue = i == count - 1 ? animatedLast : values.get(i);
                float x1 = left + offset + (i - 1) * step;
                float x2 = left + offset + i * step;
                float y1 = bottom - previousValue / 100f * height;
                float y2 = bottom - currentValue / 100f * height;
                float control = (x2 - x1) * 0.45f;
                path.cubicTo(x1 + control, y1, x2 - control, y2, x2, y2);
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            float left = 34 * d, right = getWidth() - 14 * d, top = 18 * d, bottom = getHeight() - 30 * d;
            for (int i = 0; i <= 4; i++) {
                float y = top + (bottom - top) * i / 4f;
                canvas.drawLine(left, y, right, y, gridPaint);
            }
            canvas.drawText("100", 7 * d, top + 4 * d, textPaint);
            canvas.drawText("50", 12 * d, top + (bottom - top) / 2 + 4 * d, textPaint);
            canvas.drawText("0", 17 * d, bottom + 4 * d, textPaint);
            canvas.drawText("60 s nazaj", left, getHeight() - 9 * d, textPaint);
            canvas.drawText("zdaj", right - 28 * d, getHeight() - 9 * d, textPaint);
            canvas.drawText("ocena", left, 13 * d, scorePaint);
            canvas.drawText("peak", left + 53 * d, 13 * d, peakPaint);
            if (scores.size() < 2) {
                canvas.drawText("Graf se izriše med analizo", left + 20 * d, top + (bottom - top) / 2, textPaint);
                return;
            }
            float targetScore = scores.get(scores.size() - 1);
            float targetPeak = peaks.get(peaks.size() - 1);
            animatedLastScore += (targetScore - animatedLastScore) * 0.24f;
            animatedLastPeak += (targetPeak - animatedLastPeak) * 0.24f;
            boolean stillMoving = Math.abs(targetScore - animatedLastScore) > 0.08f
                    || Math.abs(targetPeak - animatedLastPeak) > 0.08f;

            float step = (right - left) / Math.max(1, MAX_POINTS - 1);
            float offset = (MAX_POINTS - scores.size()) * step;
            float height = bottom - top;
            buildSmoothPath(scorePath, scores, animatedLastScore, left, bottom, step, offset, height);
            buildSmoothPath(peakPath, peaks, animatedLastPeak, left, bottom, step, offset, height);
            canvas.drawPath(scorePath, scorePaint);
            canvas.drawPath(peakPath, peakPaint);

            for (int i = 1; i < scores.size(); i++) {
                if (!dangers.get(i)) continue;
                float value = i == scores.size() - 1 ? animatedLastScore : scores.get(i);
                float x = left + offset + i * step;
                float y = bottom - value / 100f * height;
                canvas.drawCircle(x, y, 3.5f * d, dangerPaint);
            }
            if (stillMoving) postInvalidateOnAnimation();
        }
    }

    private static class SpectrumView extends View {
        private static final double MIN_HZ = 35.0;
        private static final double MAX_HZ = 16000.0;
        private static final double MIN_DB = -85.0;
        private static final double MAX_DB = -10.0;

        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint inspectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tooltipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final RectF tooltipRect = new RectF();
        private float[] current = new float[64];
        private float[] target = new float[64];
        private double peakHz = 0;
        private double targetPeakHz = 0;
        private boolean feedbackPeak = false;
        private boolean simpleMode = true;
        private boolean inspectionVisible = false;
        private float inspectionX = 0;
        private double inspectionHz = 0;
        private double inspectionDb = MIN_DB;

        SpectrumView(Context context) {
            super(context);
            setClickable(true);
            gridPaint.setColor(Color.rgb(62, 72, 88));
            gridPaint.setStrokeWidth(1f);
            textPaint.setColor(Color.rgb(150, 162, 178));
            textPaint.setTextSize(10.5f * getResources().getDisplayMetrics().scaledDensity);
            axisPaint.setColor(Color.rgb(181, 191, 204));
            axisPaint.setTextSize(10.5f * getResources().getDisplayMetrics().scaledDensity);
            markerPaint.setStrokeWidth(2 * getResources().getDisplayMetrics().density);
            inspectPaint.setColor(Color.WHITE);
            inspectPaint.setStrokeWidth(1.5f * getResources().getDisplayMetrics().density);
            tooltipPaint.setColor(Color.rgb(8, 11, 16));
        }

        void setSimpleMode(boolean simpleMode) {
            this.simpleMode = simpleMode;
            invalidate();
        }

        void setLevels(float[] levels, double peakHz, boolean feedbackPeak) {
            if (levels == null || levels.length == 0) return;
            if (target.length != levels.length || current.length != levels.length) {
                target = Arrays.copyOf(levels, levels.length);
                current = Arrays.copyOf(levels, levels.length);
                this.peakHz = peakHz;
            } else {
                System.arraycopy(levels, 0, target, 0, levels.length);
            }
            targetPeakHz = peakHz;
            this.feedbackPeak = feedbackPeak;
            postInvalidateOnAnimation();
        }

        private boolean animateSpectrumFrame() {
            if (target.length == 0) return false;
            if (current.length != target.length) current = Arrays.copyOf(target, target.length);
            boolean moving = false;
            for (int i = 0; i < current.length; i++) {
                float left = target[Math.max(0, i - 1)];
                float center = target[i];
                float right = target[Math.min(target.length - 1, i + 1)];
                float spatiallySmoothed = left * 0.18f + center * 0.64f + right * 0.18f;
                float difference = spatiallySmoothed - current[i];
                float alpha = difference >= 0f ? 0.36f : 0.17f;
                current[i] += difference * alpha;
                if (Math.abs(difference) > 0.0015f) moving = true;
            }
            if (targetPeakHz >= MIN_HZ && targetPeakHz <= MAX_HZ) {
                if (peakHz < MIN_HZ || peakHz > MAX_HZ) {
                    peakHz = targetPeakHz;
                } else {
                    double currentLog = Math.log(peakHz);
                    double targetLog = Math.log(targetPeakHz);
                    double delta = targetLog - currentLog;
                    currentLog += delta * 0.28;
                    peakHz = Math.exp(currentLog);
                    if (Math.abs(delta) > 0.001) moving = true;
                }
            } else {
                peakHz = targetPeakHz;
            }
            return moving;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    inspectionVisible = true;
                    updateInspectionFromX(event.getX());
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    updateInspectionFromX(event.getX());
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    updateInspectionFromX(event.getX());
                    getParent().requestDisallowInterceptTouchEvent(false);
                    performClick();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        private float plotLeft() {
            return 46f * getResources().getDisplayMetrics().density;
        }

        private float plotRight() {
            return getWidth() - 12f * getResources().getDisplayMetrics().density;
        }

        private float plotTop() {
            return 18f * getResources().getDisplayMetrics().density;
        }

        private float plotBottom() {
            return getHeight() - 38f * getResources().getDisplayMetrics().density;
        }

        private void updateInspectionFromX(float rawX) {
            if (current.length == 0 || getWidth() <= 0) return;
            float left = plotLeft();
            float right = plotRight();
            inspectionX = Math.max(left, Math.min(right, rawX));
            double ratio = (inspectionX - left) / Math.max(1f, right - left);
            int index = (int) Math.round(ratio * (current.length - 1));
            index = Math.max(0, Math.min(current.length - 1, index));
            inspectionHz = MIN_HZ * Math.pow(MAX_HZ / MIN_HZ,
                    index / (double) Math.max(1, current.length - 1));
            inspectionDb = valueToDb(current[index]);
        }

        private double valueToDb(float value) {
            float clamped = Math.max(0f, Math.min(1f, value));
            return MIN_DB + clamped * (MAX_DB - MIN_DB);
        }

        private float valueToY(float value, float top, float bottom) {
            return bottom - Math.max(0f, Math.min(1f, value)) * (bottom - top);
        }

        private float hzToX(double hz, float left, float right) {
            double position = Math.log(hz / MIN_HZ) / Math.log(MAX_HZ / MIN_HZ);
            return left + (float) Math.max(0, Math.min(1, position)) * (right - left);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            boolean spectrumMoving = animateSpectrumFrame();
            if (inspectionVisible) updateInspectionFromX(inspectionX);
            float density = getResources().getDisplayMetrics().density;
            float left = plotLeft();
            float right = plotRight();
            float top = plotTop();
            float bottom = plotBottom();

            // Navpična os: dejanska raven spektra v dBFS.
            double[] dbTicks = {-10, -30, -50, -70, -85};
            for (double dbTick : dbTicks) {
                float value = (float) ((dbTick - MIN_DB) / (MAX_DB - MIN_DB));
                float y = valueToY(value, top, bottom);
                canvas.drawLine(left, y, right, y, gridPaint);
                String label = String.format(Locale.US, "%.0f", dbTick);
                canvas.drawText(label, 8 * density, y + 4 * density, axisPaint);
            }
            canvas.drawText("dBFS", 8 * density, 13 * density, axisPaint);

            // Vodoravna logaritemska os v Hz.
            double[] hzTicks = {40, 200, 1000, 5000, 16000};
            String[] hzLabels = {"40 Hz", "200", "1 kHz", "5 k", "16 kHz"};
            for (int i = 0; i < hzTicks.length; i++) {
                float x = hzToX(hzTicks[i], left, right);
                canvas.drawLine(x, top, x, bottom, gridPaint);
                float textWidth = axisPaint.measureText(hzLabels[i]);
                float tx = Math.max(3 * density,
                        Math.min(getWidth() - textWidth - 3 * density, x - textWidth / 2));
                canvas.drawText(hzLabels[i], tx, getHeight() - 11 * density, axisPaint);
            }

            if (simpleMode) {
                String[] names = {"SUB", "LOW", "LOW-MID", "MID", "PRES.", "AIR"};
                int groups = names.length;
                float gap = 7 * density;
                float width = (right - left) / groups;
                for (int g = 0; g < groups; g++) {
                    int from = g * current.length / groups;
                    int to = Math.max(from + 1, (g + 1) * current.length / groups);
                    float value = 0;
                    for (int i = from; i < to && i < current.length; i++) {
                        value = Math.max(value, current[i]);
                    }
                    int color = value > 0.86f ? DANGER : value > 0.68f ? WARNING : ACCENT;
                    barPaint.setColor(color);
                    float x1 = left + g * width + gap / 2;
                    float x2 = left + (g + 1) * width - gap / 2;
                    float y1 = valueToY(value, top, bottom);
                    rect.set(x1, y1, x2, bottom);
                    canvas.drawRoundRect(rect, 7 * density, 7 * density, barPaint);
                    float tw = textPaint.measureText(names[g]);
                    canvas.drawText(names[g], x1 + Math.max(0, (x2 - x1 - tw) / 2),
                            bottom - 6 * density, textPaint);
                }
            } else {
                float gap = 1.2f * density;
                float barWidth = (right - left) / current.length;
                for (int i = 0; i < current.length; i++) {
                    float value = Math.max(0, Math.min(1, current[i]));
                    int color = value > 0.86f ? DANGER : value > 0.68f ? WARNING : ACCENT;
                    barPaint.setColor(color);
                    float x1 = left + i * barWidth + gap / 2;
                    float x2 = left + (i + 1) * barWidth - gap / 2;
                    float y1 = valueToY(value, top, bottom);
                    rect.set(x1, y1, x2, bottom);
                    canvas.drawRoundRect(rect, 2 * density, 2 * density, barPaint);
                }
            }

            if (peakHz >= MIN_HZ && peakHz <= MAX_HZ) {
                float x = hzToX(peakHz, left, right);
                markerPaint.setColor(feedbackPeak ? DANGER : INFO);
                canvas.drawLine(x, top, x, bottom, markerPaint);
            }

            if (inspectionVisible && current.length > 0) {
                double ratio = (inspectionX - left) / Math.max(1f, right - left);
                int index = (int) Math.round(ratio * (current.length - 1));
                index = Math.max(0, Math.min(current.length - 1, index));
                float y = valueToY(current[index], top, bottom);

                canvas.drawLine(inspectionX, top, inspectionX, bottom, inspectPaint);
                canvas.drawCircle(inspectionX, y, 4.5f * density, inspectPaint);

                String hzText = inspectionHz >= 1000
                        ? String.format(Locale.US, "%.2f kHz", inspectionHz / 1000.0)
                        : String.format(Locale.US, "%.0f Hz", inspectionHz);
                String tooltip = String.format(Locale.US, "%s   %.1f dBFS", hzText, inspectionDb);
                float textWidth = axisPaint.measureText(tooltip);
                float boxWidth = textWidth + 22 * density;
                float boxHeight = 32 * density;
                float boxLeft = inspectionX - boxWidth / 2;
                boxLeft = Math.max(5 * density, Math.min(getWidth() - boxWidth - 5 * density, boxLeft));
                float boxTop = top + 5 * density;
                tooltipRect.set(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight);
                canvas.drawRoundRect(tooltipRect, 9 * density, 9 * density, tooltipPaint);
                canvas.drawText(tooltip, boxLeft + 11 * density,
                        boxTop + 21 * density, axisPaint);
            }
            if (spectrumMoving) postInvalidateOnAnimation();
        }
    }
}
