package com.joshschriever.livenotes.activity;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.WindowManager.LayoutParams;
import android.widget.ScrollView;
import android.widget.Toast;

import com.joshschriever.livenotes.R;
import com.joshschriever.livenotes.enumeration.LongTapAction;
import com.joshschriever.livenotes.fragment.SaveDialogFragment;
import com.joshschriever.livenotes.fragment.TimeDialogFragment;
import com.joshschriever.livenotes.midi.MidiDispatcher;
import com.joshschriever.livenotes.midi.MidiMessageAdapter;
import com.joshschriever.livenotes.midi.MidiPlayer;
import com.joshschriever.livenotes.midi.MidiReceiver;
import com.joshschriever.livenotes.musicxml.MidiToXMLRenderer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import java8.util.Optional;
import uk.co.dolphin_com.seescoreandroid.SeeScoreView;
import uk.co.dolphin_com.sscore.Component;
import uk.co.dolphin_com.sscore.LoadOptions;
import uk.co.dolphin_com.sscore.SScore;
import uk.co.dolphin_com.sscore.ex.ScoreException;

import static android.os.Environment.getExternalStoragePublicDirectory;
import static java8.util.J8Arrays.stream;
import static java8.util.stream.Collectors.toList;
import static java8.util.stream.StreamSupport.stream;
import static uk.co.dolphin_com.seescoreandroid.LicenceKeyInstance.SeeScoreLibKey;

//TODO - save state?

public class LiveNotesActivity extends Activity
        implements MidiToXMLRenderer.Callbacks,
        SeeScoreView.TapNotification,
        LongTapAction.ActionVisitor,
        SaveDialogFragment.Callbacks,
        TimeDialogFragment.Callbacks {

    private static final LoadOptions LOAD_OPTIONS = new LoadOptions(SeeScoreLibKey, true);
    private static final String TAG_SAVE_DIALOG = "tagSaveDialog";
    private static final String TAG_TIME_DIALOG = "tagTimeDialog";
    private static final int PERMISSION_REQUEST_ALL_REQUIRED = 1;
    private static final List<String> REQUIRED_PERMISSIONS = new ArrayList<>();

    static {
        System.loadLibrary("stlport_shared");
        System.loadLibrary("SeeScoreLib");

        REQUIRED_PERMISSIONS.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }

    private ScrollView scrollView;
    private SeeScoreView scoreView;

    private MidiToXMLRenderer midiToXMLRenderer;
    private MidiReceiver midiReceiver;

    private Optional<LongTapAction> longTapAction = Optional.empty();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON | LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_live_notes);

        checkPermissions();
    }

    private void checkPermissions() {
        String[] permissionsToRequest = stream(REQUIRED_PERMISSIONS)
                .filter(s -> checkSelfPermission(s) == PackageManager.PERMISSION_DENIED)
                .toArray(String[]::new);
        if (permissionsToRequest.length > 0) {
            requestPermissions(permissionsToRequest, PERMISSION_REQUEST_ALL_REQUIRED);
        } else {
            initialize();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code,
                                           @NonNull String permissions[],
                                           @NonNull int[] results) {
        if (results.length > 0
                && stream(results).allMatch(r -> r == PackageManager.PERMISSION_GRANTED)) {
            initialize();
        } else {
            checkPermissions();
        }
    }

    private void initialize() {
        initializeScoreView();
        new TimeDialogFragment(this).show(getFragmentManager(), TAG_TIME_DIALOG);
    }

    @Override
    public void onTimeSet(int timeSigBeats, int timeSigBeatValue, int tempoBPM) {
        continueInitialize(timeSigBeats, timeSigBeatValue, tempoBPM);
    }

    private void continueInitialize(int timeSigBeats, int timeSigBeatValue, int tempoBPM) {
        initializeScore(timeSigBeats, timeSigBeatValue, tempoBPM);
        initializeMidi();
        onReadyToRecord();
    }

    private void initializeScoreView() {
        scoreView = new SeeScoreView(this, getAssets(), null, this);
        scrollView = (ScrollView) findViewById(R.id.scroll_view);
        scrollView.addView(scoreView);
    }

    private void initializeScore(int timeSigBeats, int timeSigBeatValue, int tempoBPM) {
        midiToXMLRenderer = new MidiToXMLRenderer(this, timeSigBeats, timeSigBeatValue, tempoBPM);
        onXMLUpdated();
    }

    private void initializeMidi() {
        midiReceiver = new MidiReceiver(this, new MidiDispatcher(
                new MidiPlayer(), new MidiMessageAdapter(midiToXMLRenderer)));
    }

    @Override
    public void onXMLUpdated() {
        setScoreXML(midiToXMLRenderer.getXML());
        scrollView.postDelayed(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN), 80);
        scrollView.postDelayed(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN), 180);
    }

    private void setScoreXML(String newXML) {
        Log.i("setScoreXML", newXML);
        try {
            setScore(SScore.loadXMLData(newXML.getBytes(), LOAD_OPTIONS));
        } catch (ScoreException e) {
            e.printStackTrace();
        }
    }

    private void setScore(SScore score) {
        new Handler(getMainLooper()).post(
                () -> scoreView.setScore(score,
                                         stream(new Boolean[score.numParts()])
                                                 .map(__ -> Boolean.TRUE).collect(toList()),
                                         1.0f));
    }

    private void onReadyToRecord() {
        midiToXMLRenderer.setReady();
        setLongTapAction(LongTapAction.START, false);
        showToast(R.string.start_playing_or_long_press, Toast.LENGTH_LONG);
    }

    @Override
    public void onStartRecording() {
        setLongTapAction(LongTapAction.STOP, false);
    }

    @Override
    public void tap(int systemIndex, int partIndex, int barIndex, Component[] components) {
        longTapAction.ifPresent(action -> action.showDescription(this));
    }

    @Override
    public void longTap(int systemIndex, int partIndex, int barIndex, Component[] components) {
        longTapAction.ifPresent(action -> action.takeAction(this));
    }

    @Override
    public void startRecording() {
        midiToXMLRenderer.startRecording();
        setLongTapAction(LongTapAction.STOP, true);
    }

    @Override
    public void stopRecording() {
        midiToXMLRenderer.stopRecording();
        setLongTapAction(LongTapAction.SAVE, true);
    }

    @Override
    public void saveScore() {
        clearLongTapAction();
        new SaveDialogFragment(this).show(getFragmentManager(), TAG_SAVE_DIALOG);
    }

    @Override
    public void resetScore() {
        clearLongTapAction();
        resetFields();
        initialize();
    }

    @Override
    public void showDescription(int descriptionResId) {
        showToast(descriptionResId, Toast.LENGTH_SHORT);
    }

    private void setLongTapAction(@NonNull LongTapAction action, boolean showDescription) {
        longTapAction = Optional.of(action);
        if (showDescription) {
            action.showDescription(this);
        }
    }

    private void clearLongTapAction() {
        longTapAction = Optional.empty();
    }

    private void showToast(int messageResId, int length) {
        Toast.makeText(this, messageResId, length).show();
    }

    @Override
    public void onSave(String fileName) {
        if (saveFile(fileName)) {
            setLongTapAction(LongTapAction.RESET, false);
            showToast(R.string.saved_reset, Toast.LENGTH_LONG);
        } else {
            setLongTapAction(LongTapAction.SAVE, false);
            showToast(R.string.error_saving, Toast.LENGTH_LONG);
        }
    }

    @Override
    public void onCancelSaving() {
        setLongTapAction(LongTapAction.RESET, true);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private boolean saveFile(String fileName) {
        try {
            File path = getExternalStoragePublicDirectory(getString(R.string.storage_dir));
            path.mkdir();
            File file = new File(path, fileName);

            FileOutputStream stream = new FileOutputStream(file);
            stream.write(midiToXMLRenderer.getXML().getBytes());
            stream.flush();
            stream.close();

            MediaScannerConnection.scanFile(this, new String[] {file.toString()}, null, null);

            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void resetFields() {
        scrollView.removeView(scoreView);
        scrollView = null;
        scoreView = null;

        midiToXMLRenderer = null;
        midiReceiver.close();
        midiReceiver = null;

        longTapAction = Optional.empty();
    }

    @Override
    public void onBackPressed() {
    }

    @Override
    protected void onDestroy() {
        if (midiReceiver != null) {
            midiReceiver.close();
        }
        super.onDestroy();
    }

}
