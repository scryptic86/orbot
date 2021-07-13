/* Copyright (c) 2009, Nathan Freitas, Orbot / The Guardian Project - https://guardianproject.info */
/* See LICENSE for licensing information */

package org.torproject.android.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.palette.graphics.Palette;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.jetradarmobile.snowfall.SnowfallView;

import net.freehaven.tor.control.TorControlCommands;
import org.json.JSONArray;
import org.torproject.android.core.Languages;
import org.torproject.android.core.LocaleHelper;
import org.torproject.android.core.ui.Rotate3dAnimation;
import org.torproject.android.core.ui.SettingsPreferencesActivity;
import org.torproject.android.tv.ui.AppConfigActivity;
import org.torproject.android.tv.ui.AppManagerActivity;
import org.torproject.android.tv.ui.onboarding.OnboardingActivity;
import org.torproject.android.service.OrbotConstants;
import org.torproject.android.service.OrbotService;
import org.torproject.android.service.TorServiceConstants;
import org.torproject.android.service.util.Prefs;
import org.torproject.android.service.vpn.TorifiedApp;
import org.torproject.android.service.vpn.VpnPrefs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Locale;
import java.util.StringTokenizer;

import static org.torproject.android.service.vpn.VpnPrefs.PREFS_KEY_TORIFIED;

public class TeeveeMainActivity extends Activity implements OrbotConstants, OnLongClickListener {

    private static final int RESULT_CLOSE_ALL = 0;
    private final static int REQUEST_VPN = 8888;
    private final static int REQUEST_SETTINGS = 0x9874;
    private final static int REQUEST_VPN_APPS_SELECT = 8889;
    private final static int LOG_DRAWER_GRAVITY = Gravity.END;
    // message types for mStatusUpdateHandler
    private final static int STATUS_UPDATE = 1;
    private static final int MESSAGE_TRAFFIC_COUNT = 2;
    private static final int MESSAGE_PORTS = 3;
    private static final float ROTATE_FROM = 0.0f;
    private static final float ROTATE_TO = 360.0f * 4f;// 3.141592654f * 32.0f;
    ArrayList<String> pkgIds = new ArrayList<>();
    AlertDialog aDialog = null;
    /* Useful UI bits */
//    private TextView lblStatus = null; //the main text display widget
    private ImageView imgStatus = null; //the main touchable image for activating Orbot
    private TextView downloadText = null;
    private TextView uploadText = null;
    private TextView mTxtOrbotLog = null;
    private Switch mBtnVPN = null;
    private Switch mBtnSnowflake = null;
    /* Some tracking bits */
    private String torStatus = null; //latest status reported from the tor service
    private Intent lastStatusIntent;  // the last ACTION_STATUS Intent received
    private SharedPreferences mPrefs = null;
    private boolean autoStartFromIntent = false;
    private RecyclerView rv;
    // this is what takes messages or values from the callback threads or other non-mainUI threads
//and passes them back into the main UI thread for display to the user
    private Handler mStatusUpdateHandler = new Handler() {

        @Override
        public void handleMessage(final Message msg) {


            Bundle data = msg.getData();

            switch (msg.what) {
                case MESSAGE_TRAFFIC_COUNT:

                    DataCount datacount = new DataCount(data.getLong("upload"), data.getLong("download"));

                    long totalRead = data.getLong("readTotal");
                    long totalWrite = data.getLong("writeTotal");

//                    downloadText.setText(formatCount(datacount.Download) + " / " + formatTotal(totalRead));
                    //                   uploadText.setText(formatCount(datacount.Upload) + " / " + formatTotal(totalWrite));

                    downloadText.setText(formatTotal(totalRead) + " \u2193");
                    uploadText.setText(formatTotal(totalWrite) + " \u2191");

                    break;
                case MESSAGE_PORTS:

                    int socksPort = data.getInt("socks");
                    int httpPort = data.getInt("http");

                    break;
                default:
                    String newTorStatus = msg.getData().getString("status");
                    String log = (String) msg.obj;

                    if (torStatus == null && newTorStatus != null) //first time status
                    {
                        updateStatus(log, newTorStatus);

                    } else
                        updateStatus(log, newTorStatus);
                    super.handleMessage(msg);
                    break;
            }
        }
    };
    /**
     * The state and log info from {@link OrbotService} are sent to the UI here in
     * the form of a local broadcast. Regular broadcasts can be sent by any app,
     * so local ones are used here so other apps cannot interfere with Orbot's
     * operation.
     */
    private BroadcastReceiver mLocalBroadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null)
                return;

            if (action.equals(TorServiceConstants.LOCAL_ACTION_LOG)) {
                Message msg = mStatusUpdateHandler.obtainMessage(STATUS_UPDATE);
                msg.obj = intent.getStringExtra(TorServiceConstants.LOCAL_EXTRA_LOG);
                msg.getData().putString("status", intent.getStringExtra(TorServiceConstants.EXTRA_STATUS));
                mStatusUpdateHandler.sendMessage(msg);

            } else if (action.equals(TorServiceConstants.LOCAL_ACTION_BANDWIDTH)) {
                long upload = intent.getLongExtra("up", 0);
                long download = intent.getLongExtra("down", 0);
                long written = intent.getLongExtra("written", 0);
                long read = intent.getLongExtra("read", 0);

                Message msg = mStatusUpdateHandler.obtainMessage(MESSAGE_TRAFFIC_COUNT);
                msg.getData().putLong("download", download);
                msg.getData().putLong("upload", upload);
                msg.getData().putLong("readTotal", read);
                msg.getData().putLong("writeTotal", written);
                msg.getData().putString("status", intent.getStringExtra(TorServiceConstants.EXTRA_STATUS));

                mStatusUpdateHandler.sendMessage(msg);

            } else if (action.equals(TorServiceConstants.ACTION_STATUS)) {
                lastStatusIntent = intent;

                Message msg = mStatusUpdateHandler.obtainMessage(STATUS_UPDATE);
                msg.getData().putString("status", intent.getStringExtra(TorServiceConstants.EXTRA_STATUS));

                mStatusUpdateHandler.sendMessage(msg);
            } else if (action.equals(TorServiceConstants.LOCAL_ACTION_PORTS)) {

                Message msg = mStatusUpdateHandler.obtainMessage(MESSAGE_PORTS);
                msg.getData().putInt("socks", intent.getIntExtra(OrbotService.EXTRA_SOCKS_PROXY_PORT, -1));
                msg.getData().putInt("http", intent.getIntExtra(OrbotService.EXTRA_HTTP_PROXY_PORT, -1));

                mStatusUpdateHandler.sendMessage(msg);

            }
        }
    };

    private static String readFromAssets(Context context, String filename) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open(filename)));

        // do reading, usually loop until end of file reading
        StringBuilder sb = new StringBuilder();
        String mLine = reader.readLine();
        while (mLine != null) {
            sb.append(mLine + '\n'); // process line
            mLine = reader.readLine();
        }
        reader.close();
        return sb.toString();
    }

    public static TorifiedApp getApp(Context context, ApplicationInfo aInfo) {
        TorifiedApp app = new TorifiedApp();

        PackageManager pMgr = context.getPackageManager();


        try {
            app.setName(pMgr.getApplicationLabel(aInfo).toString());
        } catch (Exception e) {
            return null;
        }


        app.setEnabled(aInfo.enabled);
        app.setUid(aInfo.uid);
        app.setUsername(pMgr.getNameForUid(app.getUid()));
        app.setProcname(aInfo.processName);
        app.setPackageName(aInfo.packageName);

        app.setTorified(true);

        try {
            app.setIcon(pMgr.getApplicationIcon(app.getPackageName()));


        } catch (NameNotFoundException e) {
            e.printStackTrace();
        }
        return app;
    }

    public static Bitmap drawableToBitmap(Drawable drawable) {
        Bitmap bitmap = null;

        if (drawable instanceof BitmapDrawable) {
            BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
            if (bitmapDrawable.getBitmap() != null) {
                return bitmapDrawable.getBitmap();
            }
        }

        if (drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) {
            bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); // Single color bitmap will be created of 1x1 pixel
        } else {
            bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        }

        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    /**
     * Called when the activity is first created.
     */
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mPrefs = Prefs.getSharedPrefs(getApplicationContext());

        /* Create the widgets before registering for broadcasts to guarantee
         * that the widgets exist when the status updates try to update them */
        doLayout();

        /* receive the internal status broadcasts, which are separate from the public
         * status broadcasts to prevent other apps from sending fake/wrong status
         * info to this app */
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.registerReceiver(mLocalBroadcastReceiver,
                new IntentFilter(TorServiceConstants.ACTION_STATUS));
        lbm.registerReceiver(mLocalBroadcastReceiver,
                new IntentFilter(TorServiceConstants.LOCAL_ACTION_BANDWIDTH));
        lbm.registerReceiver(mLocalBroadcastReceiver,
                new IntentFilter(TorServiceConstants.LOCAL_ACTION_LOG));
        lbm.registerReceiver(mLocalBroadcastReceiver,
                new IntentFilter(TorServiceConstants.LOCAL_ACTION_PORTS));


        boolean showFirstTime = mPrefs.getBoolean("connect_first_time", true);

        if (showFirstTime) {
            Editor pEdit = mPrefs.edit();
            pEdit.putBoolean("connect_first_time", false);
            pEdit.commit();
            startActivity(new Intent(this, OnboardingActivity.class));
        }

        /**
         * Resets previous DNS Port to the default
         */
        mPrefs.edit().putInt(VpnPrefs.PREFS_DNS_PORT, TorServiceConstants.TOR_DNS_PORT_DEFAULT).apply();

    }

    private void sendIntentToService(final String action) {

        Intent intent = new Intent(TeeveeMainActivity.this, OrbotService.class);
        intent.setAction(action);
        startService(intent);

    }

    private void stopTor() {

        Intent intent = new Intent(TeeveeMainActivity.this, OrbotService.class);
        stopService(intent);

        SnowfallView sv = findViewById(R.id.snowflake_view);
        sv.setVisibility(View.GONE);
        sv.stopFalling();
    }

    private void doLayout() {
        setContentView(R.layout.layout_main);

        setTitle(R.string.app_name);

        mTxtOrbotLog = (TextView) findViewById(R.id.orbotLog);

        imgStatus = (ImageView) findViewById(R.id.imgStatus);
        imgStatus.setOnLongClickListener(this);

        downloadText = (TextView) findViewById(R.id.trafficDown);
        uploadText = (TextView) findViewById(R.id.trafficUp);

        downloadText.setText(formatTotal(0) + " \u2193");
        uploadText.setText(formatTotal(0) + " \u2191");


        mBtnVPN = findViewById(R.id.btnVPN);

        mBtnVPN.setFocusable(true);
        mBtnVPN.setFocusableInTouchMode(true);

        mBtnVPN.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {

                if (hasFocus)
                    v.setBackgroundColor(getColor(R.color.dark_purple));
                else
                {
                    v.setBackgroundColor(getColor(R.color.med_gray));
                }
            }
        });


        boolean useVPN = Prefs.useVpn();
        mBtnVPN.setChecked(useVPN);

        //auto start VPN if VPN is enabled
        if (useVPN) {
            sendIntentToService(TorServiceConstants.ACTION_START_VPN);
        }

        mBtnVPN.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                enableVPN(isChecked);
            }
        });

        mBtnSnowflake = findViewById(R.id.btnSnowflake);
        mBtnSnowflake.setFocusable(true);
        mBtnSnowflake.setFocusableInTouchMode(true);

        mBtnSnowflake.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {

                if (hasFocus)
                    v.setBackgroundColor(getColor(R.color.dark_purple));
                else
                {
                    v.setBackgroundColor(getColor(R.color.med_gray));
                }
            }
        });


        boolean beASnowflake = Prefs.beSnowflakeProxy();
        mBtnSnowflake.setChecked(beASnowflake);

        mBtnSnowflake.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
               Prefs.setBeSnowflakeProxy(isChecked);
            }
        });

        rv = findViewById(R.id.rv);
        LinearLayoutManager llm = new LinearLayoutManager(this);
        llm.setOrientation(LinearLayoutManager.VERTICAL);
        rv.setLayoutManager(llm);
        rv.setFocusable(true);
        rv.setFocusableInTouchMode(true);


    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.onAttach(base));
    }

    /*
     * Create the UI Options Menu (non-Javadoc)
     * @see android.app.Activity#onCreateOptionsMenu(android.view.Menu)
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.orbot_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        if (item.getItemId() == R.id.menu_newnym) {
            requestNewTorIdentity();
        } else if (item.getItemId() == R.id.menu_settings) {
            Intent intent = SettingsPreferencesActivity.createIntent(this, R.xml.preferences);
            startActivityForResult(intent, REQUEST_SETTINGS);
        }
        else if (item.getItemId() == R.id.menu_about) {
            showAbout();
        }
        return super.onOptionsItemSelected(item);
    }

    private void showAbout() {

        LayoutInflater li = LayoutInflater.from(this);
        View view = li.inflate(R.layout.layout_about, null);

        String version = "";

        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName + " (Tor " + OrbotService.BINARY_TOR_VERSION + ")";
        } catch (NameNotFoundException e) {
            version = "Version Not Found";
        }

        TextView versionName = (TextView) view.findViewById(R.id.versionName);
        versionName.setText(version);

        TextView aboutOther = (TextView) view.findViewById(R.id.aboutother);

        try {
            String aboutText = readFromAssets(this, "LICENSE");
            aboutText = aboutText.replace("\n", "<br/>");
            aboutOther.setText(Html.fromHtml(aboutText));
        } catch (Exception e) {
        }

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.button_about))
                .setView(view)
                .show();
    }

    /**
     * This is our attempt to REALLY exit Orbot, and stop the background service
     * However, Android doesn't like people "quitting" apps, and/or our code may
     * not be quite right b/c no matter what we do, it seems like the OrbotService
     * still exists
     **/
    private void doExit() {
        stopTor();

        // Kill all the wizard activities
        setResult(RESULT_CLOSE_ALL);
        finish();
    }

    protected void onPause() {
        try {
            super.onPause();

            if (aDialog != null)
                aDialog.dismiss();
        } catch (IllegalStateException ise) {
            //can happen on exit/shutdown
        }
    }


    private void refreshVPNApps() {
        sendIntentToService(TorServiceConstants.ACTION_STOP_VPN);
        sendIntentToService(TorServiceConstants.ACTION_START_VPN);
    }

    private void enableVPN(boolean enable) {
        if (enable && pkgIds.size() == 0) {
            showAppPicker();
        } else {
            Prefs.putUseVpn(enable);
            Prefs.putStartOnBoot(enable);

            if (enable) {

                Intent intentVPN = VpnService.prepare(this);

                if (intentVPN != null) {
                    startActivityForResult(intentVPN, REQUEST_VPN);
                } else {
                    sendIntentToService(TorServiceConstants.ACTION_START);
                    sendIntentToService(TorServiceConstants.ACTION_START_VPN);
                }
            } else {
                sendIntentToService(TorServiceConstants.ACTION_STOP_VPN);
                stopTor(); // todo this call isn't in the main Orbot app, is it needed?
            }
        }
    }

    private synchronized void handleIntents() {
        if (getIntent() == null)
            return;

        // Get intent, action and MIME type
        Intent intent = getIntent();
        String action = intent.getAction();

        if (action == null)
            return;

        switch (action) {

            case Intent.ACTION_VIEW:
                String urlString = intent.getDataString();

                if (urlString != null) {

                    if (urlString.toLowerCase().startsWith("bridge://")) {
                        String newBridgeValue = urlString.substring(9); //remove the bridge protocol piece
                        newBridgeValue = URLDecoder.decode(newBridgeValue); //decode the value here

                        showAlert(getString(R.string.bridges_updated), getString(R.string.restart_orbot_to_use_this_bridge_) + newBridgeValue, false);

                        setNewBridges(newBridgeValue);
                    }
                }
                break;
        }

        updateStatus(null, torStatus);

        setIntent(null);

    }

    private void setNewBridges(String newBridgeValue) {

        Prefs.setBridgesList(newBridgeValue); //set the string to a preference
        Prefs.putBridgesEnabled(true);

        setResult(RESULT_OK);

//        mBtnBridges.setChecked(true);

        enableBridges(true);
    }

    /*
     * Launch the system activity for Uri viewing with the provided url
     */
    private void openBrowser(final String browserLaunchUrl, boolean forceExternal, String pkgId) {
        if (pkgId != null) {
            startIntent(pkgId, Intent.ACTION_VIEW, Uri.parse(browserLaunchUrl));
        } else if (mBtnVPN.isChecked() || forceExternal) {
            //use the system browser since VPN is on
            startIntent(null, Intent.ACTION_VIEW, Uri.parse(browserLaunchUrl));
        }
    }

    private void startIntent(String pkg, String action, Uri data) {
        Intent i;
        PackageManager pm = getPackageManager();

        try {
            if (pkg != null) {
                i = pm.getLaunchIntentForPackage(pkg);
                if (i == null)
                    throw new PackageManager.NameNotFoundException();
            } else {
                i = new Intent();
            }

            i.setAction(action);
            i.setData(data);

            if (i.resolveActivity(pm) != null)
                startActivity(i);

        } catch (PackageManager.NameNotFoundException e) {

        }
    }

    @Override
    protected void onActivityResult(int request, int response, Intent data) {
        super.onActivityResult(request, response, data);

        if (request == REQUEST_SETTINGS && response == RESULT_OK) {
            if (data != null && (!TextUtils.isEmpty(data.getStringExtra("locale")))) {

                String newLocale = data.getStringExtra("locale");
                Prefs.setDefaultLocale(newLocale);
                Languages.setLanguage(this, newLocale, true);
                //  Language.setFromPreference(this, "pref_default_locale");

                finish();

                final Handler handler = new Handler();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        //Do something after 100ms
                        startActivity(new Intent(TeeveeMainActivity.this, TeeveeMainActivity.class));

                    }
                }, 1000);


            }
        } else if (request == REQUEST_VPN_APPS_SELECT) {
            if (response == RESULT_OK &&
                    torStatus == TorServiceConstants.STATUS_ON) {
                refreshVPNApps();

                String newPkgId = data.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
                //add new entry
            }
        } else if (request == REQUEST_VPN && response == RESULT_OK) {
            sendIntentToService(TorServiceConstants.ACTION_START_VPN);
        } else if (request == REQUEST_VPN && response == RESULT_CANCELED) {
            mBtnVPN.setChecked(false);
            Prefs.putUseVpn(false);
        }


        IntentResult scanResult = IntentIntegrator.parseActivityResult(request, response, data);
        if (scanResult != null) {
            // handle scan result

            String results = scanResult.getContents();

            if (results != null && results.length() > 0) {
                try {

                    int urlIdx = results.indexOf("://");

                    if (urlIdx != -1) {
                        results = URLDecoder.decode(results, "UTF-8");
                        results = results.substring(urlIdx + 3);

                        showAlert(getString(R.string.bridges_updated), getString(R.string.restart_orbot_to_use_this_bridge_) + results, false);

                        setNewBridges(results);
                    } else {
                        JSONArray bridgeJson = new JSONArray(results);
                        StringBuffer bridgeLines = new StringBuffer();

                        for (int i = 0; i < bridgeJson.length(); i++) {
                            String bridgeLine = bridgeJson.getString(i);
                            bridgeLines.append(bridgeLine).append("\n");
                        }

                        setNewBridges(bridgeLines.toString());
                    }


                } catch (Exception e) {
                    Log.e(TAG, "unsupported", e);
                }
            }

        }

    }

    private void enableBridges(boolean enable) {
        Prefs.putBridgesEnabled(enable);

        if (torStatus == TorServiceConstants.STATUS_ON) {
            String bridgeList = Prefs.getBridgesList();
            if (bridgeList != null && bridgeList.length() > 0) {
                requestTorRereadConfig();
            }
        }
    }

    private void requestTorRereadConfig() {
        sendIntentToService(TorControlCommands.SIGNAL_RELOAD);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mBtnVPN.isChecked() != Prefs.useVpn())
            mBtnVPN.setChecked(Prefs.useVpn());

        requestTorStatus();

        if (torStatus == null)
            updateStatus("", TorServiceConstants.STATUS_STOPPING);
        else
            updateStatus(null, torStatus);

        //now you can handle the intents properly
        handleIntents();

        pkgIds.clear();
        String tordAppString = mPrefs.getString(PREFS_KEY_TORIFIED, "");
        StringTokenizer st = new StringTokenizer(tordAppString, "|");
        while (st.hasMoreTokens())
            pkgIds.add(st.nextToken());

        RVAdapter adapter = new RVAdapter();
        rv.setAdapter(adapter);

    }

    //general alert dialog for mostly Tor warning messages
    //sometimes this can go haywire or crazy with too many error
    //messages from Tor, and the user cannot stop or exit Orbot
    //so need to ensure repeated error messages are not spamming this method
    private void showAlert(String title, String msg, boolean button) {
        try {
            if (aDialog != null && aDialog.isShowing())
                aDialog.dismiss();
        } catch (Exception e) {
        } //swallow any errors

        if (button) {
            aDialog = new AlertDialog.Builder(TeeveeMainActivity.this)
                    .setIcon(R.drawable.onion32)
                    .setTitle(title)
                    .setMessage(msg)
                    .setPositiveButton(R.string.btn_okay, null)
                    .show();
        } else {
            aDialog = new AlertDialog.Builder(TeeveeMainActivity.this)
                    .setIcon(R.drawable.onion32)
                    .setTitle(title)
                    .setMessage(msg)
                    .show();
        }

        aDialog.setCanceledOnTouchOutside(true);
    }

    /**
     * Update the layout_main UI based on the status of {@link OrbotService}.
     * {@code torServiceMsg} must never be {@code null}
     */
    private void updateStatus(String torServiceMsg, String newTorStatus) {

        if (!TextUtils.isEmpty(torServiceMsg)) {
            if (torServiceMsg.contains(TorServiceConstants.LOG_NOTICE_HEADER)) {
                //     lblStatus.setText(torServiceMsg);
            }

            mTxtOrbotLog.append(torServiceMsg + '\n');

        }

        if (torStatus == null || (newTorStatus != null && newTorStatus.equals(torStatus))) {
            torStatus = newTorStatus;
            return;
        } else
            torStatus = newTorStatus;

        if (torStatus == TorServiceConstants.STATUS_ON) {

            imgStatus.setImageResource(R.drawable.toron);

            //lblStatus.setText(getString(R.string.status_activated));

            if (autoStartFromIntent) {
                autoStartFromIntent = false;
                Intent resultIntent = lastStatusIntent;

                if (resultIntent == null)
                    resultIntent = new Intent(TorServiceConstants.ACTION_START);

                resultIntent.putExtra(
                        TorServiceConstants.EXTRA_STATUS,
                        torStatus == null ? TorServiceConstants.STATUS_OFF : torStatus
                );

                setResult(RESULT_OK, resultIntent);

                finish();
                Log.d(TAG, "autoStartFromIntent finish");
            }

            if (Prefs.beSnowflakeProxy())
            {


                SnowfallView sv = findViewById(R.id.snowflake_view);
                sv.setVisibility(View.VISIBLE);
                sv.restartFalling();

            }
            else
            {
                SnowfallView sv = findViewById(R.id.snowflake_view);
                sv.setVisibility(View.GONE);
                sv.stopFalling();


            }


        } else if (torStatus == TorServiceConstants.STATUS_STARTING) {

            imgStatus.setImageResource(R.drawable.torstarting);

            if (torServiceMsg != null) {
                if (torServiceMsg.contains(TorServiceConstants.LOG_NOTICE_BOOTSTRAPPED)) {
                    //        		lblStatus.setText(torServiceMsg);
                }
            }
            //      else
            //    	lblStatus.setText(getString(R.string.status_starting_up));


        } else if (torStatus == TorServiceConstants.STATUS_STOPPING) {

            //	  if (torServiceMsg != null && torServiceMsg.contains(TorServiceConstants.LOG_NOTICE_HEADER))
            //    	lblStatus.setText(torServiceMsg);

            imgStatus.setImageResource(R.drawable.torstarting);
//            lblStatus.setText(torServiceMsg);

        } else if (torStatus == TorServiceConstants.STATUS_OFF) {

            imgStatus.setImageResource(R.drawable.toroff);
            //          lblStatus.setText("Tor v" + OrbotService.BINARY_TOR_VERSION);


        }


    }

    /**
     * Starts tor and related daemons by sending an
     * {@link TorServiceConstants#ACTION_START} {@link Intent} to
     * {@link OrbotService}
     */
    private void startTor() {
        sendIntentToService(TorServiceConstants.ACTION_START);
        mTxtOrbotLog.setText("");
    }

    /**
     * Request tor status without starting it
     * {@link TorServiceConstants#ACTION_START} {@link Intent} to
     * {@link OrbotService}
     */
    private void requestTorStatus() {
        sendIntentToService(TorServiceConstants.ACTION_STATUS);
    }

    public boolean onLongClick(View view) {

        if (torStatus == TorServiceConstants.STATUS_OFF) {
            startTor();
        } else {
            stopTor();
        }

        return true;

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mLocalBroadcastReceiver);

    }

    private String formatTotal(long count) {
        NumberFormat numberFormat = NumberFormat.getInstance(Locale.getDefault());
        // Converts the supplied argument into a string.
        // Under 2Mb, returns "xxx.xKb"
        // Over 2Mb, returns "xxx.xxMb"
        if (count < 1e6)
            return numberFormat.format(Math.round(
                    (int) (((float) count)) * 10f / 1024f / 10f)
            )
                    + getString(R.string.kb);
        else
            return numberFormat.format(Math
                    .round(
                            ((float) count)) * 100f / 1024f / 1024f / 100f
            )
                    + getString(R.string.mb);
    }

    private void requestNewTorIdentity() {
        sendIntentToService(TorControlCommands.SIGNAL_NEWNYM);

        Rotate3dAnimation rotation = new Rotate3dAnimation(ROTATE_FROM, ROTATE_TO, imgStatus.getWidth() / 2f, imgStatus.getWidth() / 2f, 20f, false);
        rotation.setFillAfter(true);
        rotation.setInterpolator(new AccelerateInterpolator());
        rotation.setDuration((long) 2 * 1000);
        rotation.setRepeatCount(0);
        imgStatus.startAnimation(rotation);
//        lblStatus.setText(getString(R.string.newnym));
    }

    public void showAppPicker() {
        startActivityForResult(new Intent(TeeveeMainActivity.this, AppManagerActivity.class), REQUEST_VPN_APPS_SELECT);

    }

    public void showAppConfig(String pkgId) {
        Intent data = new Intent(this, AppConfigActivity.class);
        data.putExtra(Intent.EXTRA_PACKAGE_NAME, pkgId);
        startActivityForResult(data, REQUEST_VPN_APPS_SELECT);
    }

    public class DataCount {
        // data uploaded
        public long Upload;
        // data downloaded
        public long Download;

        DataCount(long Upload, long Download) {
            this.Upload = Upload;
            this.Download = Download;
        }
    }

    public class RVAdapter extends RecyclerView.Adapter<RVAdapter.AppViewHolder> {


        @Override
        public int getItemCount() {

            return pkgIds.size() + 1;
        }

        @Override
        public AppViewHolder onCreateViewHolder(ViewGroup viewGroup, int i) {

            View v = LayoutInflater.from(viewGroup.getContext()).inflate(R.layout.layout_apps_listing, viewGroup, false);
            final AppViewHolder avh = new AppViewHolder(v);

            v.setFocusable(true);
            v.setFocusableInTouchMode(true);


            return avh;
        }

        @Override
        public void onBindViewHolder(final AppViewHolder avh, int i) {


            if (i < getItemCount() - 1) {
                final String pkgId = pkgIds.get(i);

                ApplicationInfo aInfo;
                try {
                    aInfo = getPackageManager().getApplicationInfo(pkgId, 0);
                    TorifiedApp app = getApp(TeeveeMainActivity.this, aInfo);

                    avh.tv.setText(app.getName());
                    avh.iv.setImageDrawable(app.getIcon());

                    Palette.generateAsync(drawableToBitmap(app.getIcon()), new Palette.PaletteAsyncListener() {
                        public void onGenerated(Palette palette) {
                            // Do something with colors...

                            int color = palette.getVibrantColor(0x000000);
                            avh.parent.setBackgroundColor(color);

                        }
                    });

                    avh.iv.setVisibility(View.VISIBLE);

                    avh.parent.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {

                            showAppConfig(pkgId);

                        }
                    });

                    avh.parent.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                        @Override
                        public void onFocusChange(View v, boolean hasFocus) {

                            if (hasFocus)
                                v.setBackgroundColor(getColor(R.color.dark_purple));
                            else
                            {
                                Palette.generateAsync(drawableToBitmap(app.getIcon()), new Palette.PaletteAsyncListener() {
                                    public void onGenerated(Palette palette) {
                                        // Do something with colors...

                                        int color = palette.getVibrantColor(0x000000);
                                        avh.parent.setBackgroundColor(color);

                                    }
                                });
                            }
                        }
                    });

                } catch (NameNotFoundException e) {
                    e.printStackTrace();
                }
            } else {
                avh.iv.setVisibility(View.INVISIBLE);
                avh.tv.setText("+ ADD APP");
                avh.parent.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {

                        showAppPicker();

                    }
                });
                avh.parent.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                    @Override
                    public void onFocusChange(View v, boolean hasFocus) {

                        if (hasFocus)
                            v.setBackgroundColor(getColor(R.color.dark_purple));
                        else
                        {
                            v.setBackgroundColor(getColor(R.color.med_gray));
                        }
                    }
                });
            }
        }

        public class AppViewHolder extends RecyclerView.ViewHolder {

            ImageView iv;
            TextView tv;
            View parent;

            AppViewHolder(View itemView) {
                super(itemView);
                parent = itemView;
                iv = itemView.findViewById(R.id.itemicon);
                tv = itemView.findViewById(R.id.itemtext);

            }

        }
    }
}
