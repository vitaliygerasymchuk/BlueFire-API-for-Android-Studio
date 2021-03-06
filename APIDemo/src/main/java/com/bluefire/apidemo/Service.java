package com.bluefire.apidemo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.bluefire.api.BlueFire;
import com.bluefire.api.ConnectionStates;
import com.bluefire.api.Const;
import com.bluefire.api.RetrievalMethods;
import com.bluefire.api.Truck;


public class Service
{
    private BlueFire blueFire;

    private ServiceThread serviceThread;

    private boolean serviceIsRunning;

    private ConnectionStates connectionState = ConnectionStates.NotConnected;

    private boolean isKeyOn;

    private boolean isConnecting;
    private boolean isConnected;

    private boolean IsRetrievingEngineVIN;

    private RetrievalMethods retrievalMethod;
    private int retrievalInterval;

    // Application settings
    private boolean appUseBLE;
    private boolean appUseBT21;

    private boolean appIgnoreJ1939;
    private boolean appIgnoreJ1708;

    private int appLedBrightness;

    private int appDiscoveryTimeOut;

    private int appMaxConnectAttempts;
    private int appMaxReconnectAttempts;

    private String appAdapterId = "";
    private boolean appConnectToLastAdapter;

    private boolean appOptimizeDataRetrieval = false;

    private Context serviceContext;

    public Service(Context context)
    {
        serviceContext = context; // the API requires a context

        // Set app variables
        appUseBLE = true;
        appUseBT21 = false;

        appIgnoreJ1939 = false;
        appIgnoreJ1708 = true;

        appDiscoveryTimeOut = 10 * Const.OneSecond;

        appMaxConnectAttempts = 10;
        appMaxReconnectAttempts = 5;

        appLedBrightness = 100;

        appConnectToLastAdapter = false;

        appOptimizeDataRetrieval = true;
    }

    public void startService()
    {
        // Initiate the API
        blueFire = new BlueFire(serviceContext, eventHandler);

        // Simulate a service
        serviceIsRunning = true;

        serviceThread = new ServiceThread();
        serviceThread.start();
    }

    public void stopService()
    {
        serviceIsRunning = false;

        // Clear previous data from the CAN Filter
        blueFire.StopDataRetrieval();

        disconnectAdapter();

        blueFire.Dispose();
    }

    private class ServiceThread extends Thread
    {
        public void run()
        {
            // Connect to the adapter
            connectAdapter();
        }
    }

    // Connect
    public void connectAdapter()
    {
        try
        {
            isConnecting = true;
            isConnected = false;

            connectionState = ConnectionStates.NA;

            logNotifications("Connecting...");

            // Initialize adapter properties (in case they were changed)
            initializeAdapter();

            // Note, this is a blocking call and must run in it's own thread.
            blueFire.Connect();
        }
        catch (Exception ex) {}
    }

    private void initializeAdapter()
    {
        // Set Bluetooth adapter type
        blueFire.UseBLE = appUseBLE;
        blueFire.UseBT21 = appUseBT21;

        // Set to ignore data bus settings
        blueFire.SetIgnoreJ1939(appIgnoreJ1939);
        blueFire.SetIgnoreJ1708(appIgnoreJ1708);

        // Set the Bluetooth discovery timeout.
        // Note, depending on the number of Bluetooth devices present on the mobile device,
        // discovery could take a long time.
        // Note, if this is set to a high value, the app needs to provide the user with the
        // capability of canceling the discovery.
        blueFire.SetDiscoveryTimeout(appDiscoveryTimeOut);

        // Set number of Bluetooth connection attempts.
        // Note, if the mobile device does not connect, try setting this to a value that
        // allows for a consistent connection. If you're using multiple adapters and have
        // connection problems, un-pair all devices before connecting.
        blueFire.SetMaxConnectAttempts(appMaxConnectAttempts);
        blueFire.SetMaxReconnectAttempts(appMaxReconnectAttempts);

        // Set the Bluetooth adapter id and the 'connect to last adapter' setting
        blueFire.SetAdapterId(appAdapterId);
        blueFire.SetConnectToLastAdapter(appConnectToLastAdapter);

        // Set to optimize data retrieval
        blueFire.SetOptimizeDataRetrieval(appOptimizeDataRetrieval);
    }

    private void disconnectAdapter()
    {
        try
        {
            // Note, with Firmware 3.11 there is no need to wait for the adapter
            // to disconnect.
            boolean WaitForDisconnect = false; // just for code clarity
            blueFire.Disconnect(WaitForDisconnect);
        }
        catch(Exception e) {}
    }

    private void adapterConnected()
    {
        logNotifications("Adapter connected.");

        isConnected = true;
        isConnecting = false;

        // Get adapter data
        getAdapterData();
    }

    // Start retrieving data after connecting to the adapter
    private void getAdapterData()
    {
        // Check for an incompatible version.
        if (!blueFire.IsCompatible())
        {
            logNotifications("Incompatible Adapter.");

            disconnectAdapter();
            return;
        }

        // Set to receive notifications from the adapter.
        // Note, this should only be used during testing.
        blueFire.SetNotificationsOn(true);

        // Set the adapter led brightness
        blueFire.SetLedBrightness(appLedBrightness);

        // Get the adapter id
        appAdapterId = blueFire.AdapterId();

        // Get any adapter messages
        blueFire.GetMessages();

        // Start retrieving truck data
        getTruckData();
    }

    private void getTruckData()
    {
        // Clear previous data from the CAN Filter
        blueFire.StopDataRetrieval();

        if (Truck.EngineVIN == Const.NA)
        {
            IsRetrievingEngineVIN = true;
            blueFire.GetEngineVIN();
        }

        retrievalMethod = RetrievalMethods.OnChange; // do not use OnInterval with this many data requests
        retrievalInterval = blueFire.MinInterval(); // should be MinInterval or greater with this many requests
        int hoursInterval = 30 * Const.OneSecond; // hours only change every 3 minutes

        // Request data from the adapter.
        // Note, be careful not to request too much data at one time otherwise you run the risk of filling up
        // the CAN Filter buffer. You can experiment with combining data retrievals to determine how much you can
        // request before filling the CAN Filter buffer (you get an error if you do).

        blueFire.GetEngineData1(retrievalMethod, retrievalInterval); // RPM, Percent Torque, Driver Torque, Torque Mode
        blueFire.GetEngineData2(retrievalMethod, retrievalInterval); // Percent Load, Accelerator Pedal Position
        blueFire.GetEngineData3(retrievalMethod, retrievalInterval); // Vehicle Speed, Max Set Speed, Brake Switch, Clutch Switch, Park Brake Switch, Cruise Control Settings and Switches
        blueFire.GetOdometer(retrievalMethod, retrievalInterval); // Distance and Odometer
        blueFire.GetEngineHours(retrievalMethod, hoursInterval); // Total Engine Hours, Total Idle Hours
        blueFire.GetBrakeData(retrievalMethod, retrievalInterval); // Application Pressure, Primary Pressure, Secondary Pressure
        blueFire.GetBatteryVoltage(retrievalMethod, retrievalInterval); // Battery Voltage
        blueFire.GetFuelData(retrievalMethod, retrievalInterval); // Fuel Levels, Fuel Used, Idle Fuel Used, Fuel Rate, Instant Fuel Economy, Avg Fuel Economy, Throttle Position
        blueFire.GetTemps(retrievalMethod, retrievalInterval); // Oil Temp, Coolant Temp, Intake Manifold Temperature
        blueFire.GetPressures(retrievalMethod, retrievalInterval); // Oil Pressure, Coolant Pressure, Intake Manifold(Boost) Pressure
        blueFire.GetCoolantLevel(retrievalMethod, retrievalInterval); // Coolant Level
    }

    private int TimeToWrite = 9999;

    private void checkTruckData()
    {
        // Check the data you requested to see which one changed that triggered the DataAvailable
        // event. If you're not concerned with data throughput for processing the data, you can just
        // process all the data whether it changed or not.

        if (IsRetrievingEngineVIN && Truck.EngineVIN != Const.NA)
        {
            IsRetrievingEngineVIN = false;
            blueFire.StopRetrievingEngineVIN();
            logNotifications("Engine VIN=" + Truck.EngineVIN);
        }

        if (TimeToWrite > 50)
        {
            TimeToWrite = 0;
            logNotifications("RPM=" + Truck.RPM);
        }
        else
            TimeToWrite ++;

//        if (Truck.RPM > 0)
//            logNotifications("RPM=" + Truck.RPM);
//        logNotifications("PctLoad=" + Truck.PctLoad);
//        logNotifications("Speed=" + Truck.Speed);
    }

    private void checkKeyState()
    {
        boolean keyIsOn = (isConnected && (blueFire.IsCANAvailable() || blueFire.IsJ1708Available()));

        if (isKeyOn != keyIsOn)
        {
            if (keyIsOn)
                logNotifications("Key is On");
            else
                logNotifications("Key is Off");

            isKeyOn = keyIsOn;
        }
    }

    private void adapterNotAuthenticated()
    {
        logNotifications("Adapter not authenticated.");

        adapterNotConnected();
    }

    private void adapterDisconnected()
    {
        logNotifications("Adapter disconnected.");

        adapterNotConnected();
    }

    private void adapterNotConnected()
    {
        logNotifications("Adapter not connected.");

        isConnected = false;
        isConnecting = false;

        logStatus();
    }

    private void adapterReconnecting()
    {
        if (!isConnecting)
            logNotifications("App lost connection to the Adapter. Reason is " + blueFire.ReconnectReason() + ".");

        logNotifications("Adapter re-connecting.");

        isConnected = false;
        isConnecting = true;
    }

    private void adapterReconnected()
    {
        logNotifications("Adapter re-connected.");

        adapterConnected();
    }

    private void adapterNotReconnected()
    {
        logNotifications("Adapter not re-connected.");

        adapterNotConnected();
    }

    private void j1708Restarting()
    {
        // Re-retrieve truck data
        getTruckData();
    }

    // BlueFire Event Handler
    private final Handler eventHandler = new Handler()
    {
        @Override
        @SuppressLint("HandlerLeak")
        public void handleMessage(Message msg)
        {
            try
            {
                logStatus();

                switch (blueFire.ConnectionState)
                {
                    case NotConnected:
                        if (isConnecting || isConnected) // only show once
                            adapterNotConnected();
                        break;

                    case Connecting:
                        if (blueFire.IsReconnecting())
                            if (!isConnecting) // only show once
                                adapterReconnecting();
                        break;

                    case Discovering:
                        // Status only
                        break;

                    case AdapterConnected:
                        // Status only
                        break;

                    case Authenticated:
                        if (!isConnected) // only show once
                            adapterConnected();
                        break;

                    case NotAuthenticated:
                        adapterNotAuthenticated();
                        break;

                    case Disconnecting:
                        // Status only
                        break;

                    case Disconnected:
                        if (isConnecting || isConnected) // only show once
                            adapterDisconnected();
                        break;

                    case Reconnecting:
                        adapterReconnecting();
                        break;

                    case Reconnected:
                        if (isConnecting)// only show once
                            adapterReconnected();
                        break;

                    case NotReconnected:
                        if (isConnecting)// only show once
                            adapterNotReconnected();
                        break;

                    case J1708Restarting:
                        j1708Restarting();
                        break;

                    case Notification:
                        logAPINotifications();
                        break;

                    case AdapterMessage:
                        logAdapterMessages();
                        break;

                    case CANFilterFull:
                        logNotifications("The CAN Filter is Full. Some data will not be retrieved.");
                        break;

                    case DataError:
                        // Ignore, handled by Reconnecting
                        break;

                    case CommTimeout:
                    case ConnectTimeout:
                    case AdapterTimeout:
                        if (isConnecting || isConnected) // only show once
                        {
                            blueFire.Disconnect();
                            adapterNotConnected();
                            logNotifications("The Adapter has Timed Out.");
                        }
                        break;

                    case SystemError:
                        if (isConnecting || isConnected) // only show once
                        {
                            blueFire.Disconnect();
                            adapterNotConnected();
                            logSystemError();
                        }
                        break;

                    case DataChanged:
                        checkTruckData();
                }

            }
            catch (Exception e) {}
        }
    };

    private void logStatus()
    {
        // Check for a change of the connection state
        if (connectionState != blueFire.ConnectionState)
        {
            connectionState = blueFire.ConnectionState;
            logNotifications(connectionState.toString());
        }

        // Show any error message from the adapter
        if (!blueFire.NotificationMessage().equals(""))
        {
            logNotifications(blueFire.NotificationMessage());
            blueFire.ClearNotificationMessage();
        }
    }

    private void logSystemError()
    {
        logNotifications("System Error");

        logAPINotifications();
    }

    private String logAPINotifications()
    {
        String Message = blueFire.NotificationMessage();

        if (!Message.equals(""))
        {
            if (!blueFire.NotificationLocation().equals(""))
                Message = blueFire.NotificationLocation() + " - " + Message;

            blueFire.ClearNotificationMessage();

            logNotifications(Message);
        }

        String AdapterMessage = logAdapterMessages();
        if (!AdapterMessage.equals(""))
            Message += Const.CrLf + AdapterMessage;

        return Message;
    }

    private String logAdapterMessages()
    {
        String Message = blueFire.Message();

        if (!Message.equals(""))
        {
            blueFire.ClearMessages();

            logNotifications(Message);
        }
        return Message;
    }

    private void logNotifications(String notification)
    {
        Log.d("BlueFire", notification);
    }

}
