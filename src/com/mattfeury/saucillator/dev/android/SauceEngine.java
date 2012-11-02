package com.mattfeury.saucillator.dev.android;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

import com.mattfeury.saucillator.dev.android.R;
import com.mattfeury.saucillator.dev.android.instruments.*;
import com.mattfeury.saucillator.dev.android.settings.ModifyInstrument;
import com.mattfeury.saucillator.dev.android.settings.Settings;
import com.mattfeury.saucillator.dev.android.sound.*;
import com.mattfeury.saucillator.dev.android.tabs.*;
import com.mattfeury.saucillator.dev.android.utilities.*;
import com.mattfeury.saucillator.dev.android.visuals.*;
import com.mattfeury.saucillator.dev.android.services.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.os.Vibrator;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.util.Log;
import android.view.*;
import android.view.View.OnTouchListener;

/*
 * Main activity for the App. This class has two main purposes:
 * 
 * 1. Handle user input.
 * 2. Delegate events (based user input�or otherwise) between the AudioEngine and the SauceView.
 * 
 * It also currently handles all the Activity necessities: menus, dialogs, etc.
 */
public class SauceEngine extends Activity implements OnTouchListener {
    public static final String TAG = "Sauce";

    //defaults
    public static int TRACKPAD_GRID_SIZE = 12;
    public final static int TRACKPAD_SIZE_MAX = 16;

    private boolean init = false;

    // which finger ID corresponds to which fingerable layout element. e.g. buttons, knobs, etc.
    private ConcurrentHashMap<Integer, Fingerable> fingersById = new ConcurrentHashMap<Integer, Fingerable>();

    private SubMenu instrumentMenu;
    private final int instrumentMenuId = 9;
    public static final String DATA_FOLDER = "sauce/";
    public static final int MODIFY_ACTION = 1;

    private static final String tutorialName = "showAlfredoTutorial";
    
    private static final int BACKPRESS_DIALOG = 0,
                             TUTORIAL_DIALOG = 1;

    private Object mutex = new Object();

    private SauceView view;
    private TabManager tabManager;
    private AudioEngine audioEngine;

    @Override
    public void onCreate(Bundle savedInstanceState) {
      Log.i(TAG, "Brewing sauce...");
      super.onCreate(savedInstanceState);

      // Show tutorial on first load
      SharedPreferences prefs = getPreferences(MODE_PRIVATE);
      boolean shouldShowTutorial = prefs.getBoolean(tutorialName, true);
      if (shouldShowTutorial) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(tutorialName, false);
        editor.commit();

        showDialog(TUTORIAL_DIALOG);
      }

      requestWindowFeature(Window.FEATURE_NO_TITLE);
      setContentView(R.layout.main);
      view = (SauceView)findViewById(R.id.sauceview);
      view.setOnTouchListener(this);

      VibratorService.setup((Vibrator) getSystemService(Context.VIBRATOR_SERVICE));
      ToastService.setup(this);

      this.audioEngine = new AudioEngine(this, mutex);

      // We wait until the dac is spun up to create the param handlers since
      // they require certain DAC elements (e.g. EQ). We can't do it in the DAC thread
      // because only the thread that spawned the view can redraw it.
      synchronized(mutex) {
        try {
          if (! init)
            mutex.wait();
        } catch (InterruptedException e) {
          e.printStackTrace();
        }

        init = true;

        this.tabManager = new TabManager();
        
        view.addDrawable(tabManager);
        tabManager.addTab(new FxTab(audioEngine));
        tabManager.addTab(new LooperTab(audioEngine));

        // TODO setup visual layout that depends on audio shtuff
      }
    }

    protected Dialog onCreateDialog(int id){
      AlertDialog.Builder builder = new AlertDialog.Builder(this);
      switch(id) {
        case BACKPRESS_DIALOG:
          builder
            .setTitle("Exit or hide?")
            .setMessage("Should the app stay awake and keep playing music? Keeping the app playing in the background may cause popping.")
            .setCancelable(true)
            .setPositiveButton("Quit",
                new DialogInterface.OnClickListener() {
                  public void onClick(DialogInterface dialog, int id) {
                    SauceEngine.this.finish();
                  }
            })
            .setNegativeButton("Hide",
                new DialogInterface.OnClickListener() {
                  public void onClick(DialogInterface dialog, int which) {
                    moveTaskToBack(true);
                  }
            });
          break;
        case TUTORIAL_DIALOG:
          builder
            .setTitle("Saucillator 1.0 Alfredo")
            .setView(LayoutInflater.from(this).inflate(R.layout.tutorial_dialog,null))
            .setCancelable(false)
            .setNeutralButton("Good Juice. Let's Sauce.", new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface dialog, int id) {
            }});
          break;
        default:
      }
      AlertDialog alert = builder.create();
      return alert;
    }    

    protected void onDestroy() {
    	android.os.Process.killProcess(android.os.Process.myPid());
    }
    
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
      super.onConfigurationChanged(newConfig);
      setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    /**
     * That main goodness. Handles touch events and gets properties of them to change the oscillators
     */
    @Override
    public boolean onTouch(View v, MotionEvent event) {
      if (! init) return false;

      //int maxHeight = v.getMeasuredHeight();
      //int maxWidth = v.getMeasuredWidth();

      int action = event.getAction();
      int actionCode = action & MotionEvent.ACTION_MASK;
      
      if (actionCode == MotionEvent.ACTION_UP && audioEngine.isPlaying()) { //last finger lifted. stop playback
        fingersById.clear();
        audioEngine.stopAllOscillators();
        view.clearFingers();

        return true;
      }

      int pointerCount = event.getPointerCount();
      final int actionIndex = event.getActionIndex();
      final int actionId = event.getPointerId(actionIndex);

      /*
       * Loop through each finger.
       * We go backwards because of the buttons. If a button is pressed and held, the next finger call
       * will be a POINTER_DOWN call, but the first button will interpret it first since it has a smaller index. That's bad.
       */
      for (int i = pointerCount - 1; i > -1; i--) {
        final int id = event.getPointerId(i);
        if (id < 0)
          continue;

        float y = event.getY(i);
        float x = event.getX(i);

        Fingerable controlled = fingersById.get(id);

        if (controlled != null) {
          Box<Fingerable> fingered = controlled.handleTouch(id, event);

          // It may return empty if it no longer wishes to handle touches
          if (! fingered.isDefined()) {
            fingersById.remove(id);

            if (controlled instanceof Drawable) {
              view.removeDrawable(((Drawable)controlled));
            }
          }
        } else if (view.isInPad(x,y)) {
          handleTouchForOscillator(id, event);
        } else {
          handleTouchForController(id, event);
        }
        view.invalidate();
      }

      if (actionCode == MotionEvent.ACTION_POINTER_UP) {
        fingersById.remove(actionId);
      }

      return true; // indicate event was handled
    }

    private void handleTouchForOscillator(int id, MotionEvent event) {
      ComplexOsc osc = audioEngine.getOrCreateOscillator(id);
      Fingerable controlled = fingersById.get(id);
      boolean fingerDefined = controlled != null;

      if (osc == null || (! osc.equals(controlled) && (fingerDefined || isFingered(osc)))) return;

      final int index = event.findPointerIndex(id);
      final int y = (int) event.getY(index);
      final int x = (int) event.getX(index);

      FingeredOscillator fingerableOsc = new FingeredOscillator(view, osc, x, y);
      fingersById.put(id, fingerableOsc);
      view.addDrawable(fingerableOsc);

      fingerableOsc.handleTouch(id, event);
    }
    
    private void handleTouchForController(final int id, MotionEvent event) {
      Fingerable controlled = fingersById.get(id);

      if (controlled == null) {
        Box<Fingerable> fingered = tabManager.handleTouch(id, event);
        fingered.foreach(new EachFunc<Fingerable>() {
          public void func(Fingerable k) {
            fingersById.put((Integer)id, k);
          }            
        });
      } else {
        controlled.handleTouch(id, event);
      }

      view.invalidate();
    }

    public boolean isFingered(Object obj) {
      return (obj != null && fingersById.containsValue(obj));
    }


    /**
     * Settings handlers
     */
    private boolean launchSettings() {
    	Intent intent = new Intent(SauceEngine.this, Settings.class);
    	//intent.putExtra("octave", octave);
    	//intent.putExtra("note", note);
    	//intent.putExtra("file name", WavWriter.filePrefix);
    	//intent.putExtra("visuals", view.getVisuals());
      //intent.putExtra("scale", scaleId);
    	//startActivityForResult(intent, 0);
    	return true;
    }
    private void launchModifyInstrument(boolean create) {
    	Intent intent = new Intent(SauceEngine.this, ModifyInstrument.class);
    	intent.putExtra("createNew", create);
    	startActivityForResult(intent, SauceEngine.MODIFY_ACTION);
    }
    private void editInstrument() {
      launchModifyInstrument(false);
    }
    private void createInstrument() {
      launchModifyInstrument(true);
    }
    // Called when settings activity ends. Updates proper params
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
      /*if (requestCode == 0 && data != null) {
        Bundle extras = data.getExtras();

        if (extras != null) {
          WavWriter.filePrefix = extras.getString("file name");
          note = extras.getInt("note");
          octave = extras.getInt("octave");
          scaleId = extras.getString("scale");

          view.setVisuals(extras.getBoolean("visuals"));
          selectScale(scaleId);
        }
      } else if (requestCode == SauceEngine.MODIFY_ACTION) {
        if (ModifyInstrument.modifying == null)
          return;

        currentOscillator = ModifyInstrument.modifying;
        resetOscillators();
        setupParamHandlers();
      }*/
      //updateOscSettings();
    }

    /**
     * Menu handlers
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
      MenuInflater inflater = getMenuInflater();
      inflater.inflate(R.menu.menu, menu);

      instrumentMenu = menu.findItem(R.id.selectInstrument).getSubMenu();

      return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
      boolean result = super.onPrepareOptionsMenu(menu);

      instrumentMenu.clear();

      ArrayList<String> instruments = InstrumentManager.getAllInstrumentNames(getAssets());
      String[] names = instruments.toArray(new String[0]);
      int i = 0;
      for (String name : names) {
        instrumentMenu.add(instrumentMenuId, i, i, name);
        i++;
      }

      instrumentMenu.setGroupCheckable(instrumentMenuId, false, true);
      
      return result;
    }

    public boolean onOptionsItemSelected(MenuItem item) {
    	switch (item.getGroupId()) {
        case instrumentMenuId:
          return instrumentSelection(item);
    	}
    	switch (item.getItemId()) {
        case R.id.createInstrumentItem:
          createInstrument();
          return true;
        case R.id.editInstrumentItem:
          editInstrument();
          return true;
    		case R.id.settings:
    			return launchSettings();
    		case R.id.record:
    			return record(item);
        case R.id.quit:
          onDestroy();
          return true;
      }
      return false;
    }
    
    @Override
    public void onBackPressed() {
      showDialog(BACKPRESS_DIALOG);
    }

    private boolean record(MenuItem item) {
      audioEngine.record();
      return true;
    }

    private void selectScale(String scaleId) {
      audioEngine.setScaleById(scaleId);
    }

    private boolean instrumentSelection(MenuItem item) {
    	if (item.isChecked())
    		return true;

      String name = (String) item.getTitle();
      ComplexOsc newOsc = InstrumentManager.getInstrument(getAssets(), name);

      if (newOsc == null) {
        ToastService.makeToast("Bad Instrument.");
        return false;
      } else {
        audioEngine.setOscillator(newOsc);
        return true;
      }
    }
}
