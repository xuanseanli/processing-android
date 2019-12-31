/* -*- mode: java; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
 Part of the Processing project - http://processing.org

 Copyright (c) 2013-16 The Processing Foundation

 This program is free software; you can redistribute it and/or modify
 it under the terms of the GNU General Public License version 2
 as published by the Free Software Foundation.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program; if not, write to the Free Software Foundation,
 Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package processing.mode.android;

import processing.app.exec.ProcessResult;
import processing.mode.android.EmulatorController.State;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

//import processing.app.EditorConsole;

/**
 * <pre> AndroidEnvironment env = AndroidEnvironment.getInstance();
 * AndroidDevice n1 = env.getHardware();
 * AndroidDevice emu = env.getEmulator();</pre>
 * @author Jonathan Feinberg &lt;jdf@pobox.com&gt;
 *
 */
class Devices {
  private static final String DEVICE_PERMISSIONS_URL = 
	"https://developer.android.com/studio/run/device.html";
	  
  private static final Devices INSTANCE = new Devices();

  private static final String BT_DEBUG_PORT = "4444";
  
  private boolean showPermissionsErrorMessage = true;
  
  private AndroidSDK sdk;
  
  private Device selectedDevice;

  public static Devices getInstance() {
    return INSTANCE;
  }

  private final Map<String, Device> devices =
    new ConcurrentHashMap<String, Device>();
  private final ExecutorService deviceLaunchThread =
    Executors.newSingleThreadExecutor();

  public void setSDK(AndroidSDK sdk) {
    this.sdk = sdk;
  }

  public AndroidSDK getSDK() {
    return sdk;
  }  
  
  public Device getSelectedDevice() {
    return selectedDevice;
  }

  public void setSelectedDevice(Device selectedDevice) {
    this.selectedDevice = selectedDevice;
  }

  public void killAdbServer() {
    System.out.print("Shutting down any existing adb server...");
    System.out.flush();
    try {
      sdk.runADB("kill-server");
      System.out.println(" Done.");
    } catch (final Exception e) {
      System.err.println("/nDevices.killAdbServer() failed.");
      e.printStackTrace();
    }
  }

  public void startAdbServer() {
    System.out.print("Starting a new adb server...");
    System.out.flush();
    try {
      sdk.runADB("start-server");
      System.out.println(" Done.");
    } catch (final Exception e) {
      System.err.println("/nDevices.startAdbServer() failed.");
      e.printStackTrace();
    }
  }  
  
  public void enableBluetoothDebugging() {
    final Devices devices = Devices.getInstance();
    java.util.List<Device> deviceList = devices.findMultiple(false);
    
    if (deviceList.size() != 1) {
      // There is more than one non-emulator device connected to the computer,
      // but don't know which one the watch could be paired to... or the watch
      // is already paired to the phone, in which case we don't need to keep
      // trying to connect.
      return;
    }
    Device device = deviceList.get(0);
    try {
      // Try Enable debugging over bluetooth
      // http://developer.android.com/training/wearables/apps/bt-debugging.html
      sdk.runADB("-s", device.getId(), "forward", "tcp:" + BT_DEBUG_PORT, 
                "localabstract:/adb-hub");
      sdk.runADB("connect", "127.0.0.1:" + BT_DEBUG_PORT);
    } catch (final Exception e) {
      e.printStackTrace();
    }    
  }
  

  private Devices() {
    if (processing.app.Base.DEBUG) {
      System.out.println("Starting up Devices");
    }
//    killAdbServer();
    Runtime.getRuntime().addShutdownHook(
      new Thread("processing.mode.android.Devices Shutdown") {
        public void run() {
          //System.out.println("Shutting down Devices");
          //System.out.flush();
          for (Device device : new ArrayList<Device>(devices.values())) {
            device.shutdown();
          }
          // Don't do this, it'll just make Eclipse and others freak out.
          //killAdbServer();
        }
      });
  }


  public Future<Device> getEmulator(final boolean wear) {
    final Callable<Device> androidFinder = new Callable<Device>() {
      public Device call() throws Exception {
        return blockingGetEmulator( wear);
      }
    };
    final FutureTask<Device> task = new FutureTask<Device>(androidFinder);
    deviceLaunchThread.execute(task);
    return task;
  }


  private final Device blockingGetEmulator(final boolean wear) {
    String port = AVD.getPreferredPort(wear);
    Device emu = find(true, port);
    if (emu != null) {
      return emu;
    }

    EmulatorController emuController = EmulatorController.getInstance(wear);
    if (emuController.getState() == State.RUNNING) {
      // The emulator is in running state, but did not find any emulator device,
      // to the most common cause is that it was closed, so we will re-launch it.
      emuController.setState(State.NOT_RUNNING);
    }
    
    if (emuController.getState() == State.NOT_RUNNING) {
      try {
        emuController.launch(sdk, wear); // this blocks until emulator boots
      } catch (final IOException e) {
        System.err.println("Problem while launching emulator.");
        e.printStackTrace(System.err);
        return null;
      }
    } else {
      return null;
    }

    while (!Thread.currentThread().isInterrupted()) {
      //      System.err.println("AndroidEnvironment: looking for emulator in loop.");
      //      System.err.println("AndroidEnvironment: emulatorcontroller state is "
      //          + emuController.getState());
      if (emuController.getState() == State.NOT_RUNNING) {
        System.err.println("Error while starting the emulator. (" +
                           emuController.getState() + ")");
        return null;
      }
      emu = find(true, port);
      if (emu != null) {
        //        System.err.println("AndroidEnvironment: returning " + emu.getId()
        //            + " from loop.");
        return emu;
      }
      try {
        Thread.sleep(2000);
      } catch (final InterruptedException e) {
        System.err.println("Devices: interrupted in loop.");
        return null;
      }
    }
    return null;
  }


  private Device find(final boolean wantEmulator, final String port) {
    refresh();
    synchronized (devices) {
      for (final Device device : devices.values()) {
        if (port != null && device.getName().indexOf(port) == -1) continue;
        final boolean isEmulator = device.getId().contains("emulator");
        if ((isEmulator && wantEmulator) || (!isEmulator && !wantEmulator)) {
          return device;
        }
      }
    }
    return null;
  }

  public List<Device> findMultiple(final boolean wantEmulator) {
    List<Device> deviceList = new ArrayList<Device>();

    refresh();
    synchronized (devices) {
      for (final Device device : devices.values()) {
        final boolean isEmulator = device.getId().contains("emulator");
        if ((isEmulator && wantEmulator) || (!isEmulator && !wantEmulator)) {
          deviceList.add(device);
        }
      }
    }

    return deviceList;
  }

  /**
   * @return the first Android hardware device known to be running, or null if there are none.
   */
  public Future<Device> getHardware() {
    Device device = getSelectedDevice();
    if (device == null || !device.isAlive()) device = blockingGetHardware();
    return getHardware(device);
  }

  public Future<Device> getHardware(final Device device) {
    final Callable<Device> androidFinder = new Callable<Device>() {
      public Device call() throws Exception {
        return device;
      }
    };
    final FutureTask<Device> task =
        new FutureTask<Device>(androidFinder);
    deviceLaunchThread.execute(task);
    return task;
  }

  private final Device blockingGetHardware() {
    Device hardware = find(false, null);
    if (hardware != null) {
      return hardware;
    }
    while (!Thread.currentThread().isInterrupted()) {
      try {
        Thread.sleep(2000);
      } catch (final InterruptedException e) {
        return null;
      }
      hardware = find(false, null);
      if (hardware != null) {
        return hardware;
      }
    }
    return null;
  }


  private void refresh() {
    final List<String> activeDevices = list();
    for (final String deviceId : activeDevices) {
      if (!devices.containsKey(deviceId)) {
        Device device = new Device(this, deviceId); 
        addDevice(device);
      }
    }
  }


  private void addDevice(final Device device) {
    //    System.err.println("AndroidEnvironment: adding " + device.getId());
    try {
      device.initialize();
      if (devices.put(device.getId(), device) != null) {
        // TODO: Silently add existing device, this may be indicating, like in 
        // deviceRemoved() below, different threads trying to add the same 
        // device. Does not seem to have any negative effect.
//        throw new IllegalStateException("Adding " + device
//            + ", which already exists!");
      }
    } catch (final Exception e) {
      System.err.println("While initializing " + device.getId() + ": " + e);
    }
  }


  void deviceRemoved(final Device device) {
    String id = device.getId();
    if (devices.containsKey(id)) {
      devices.remove(device.getId());
    } else {
      // TODO: Device already removed, don't throw exception as this seems to 
      // happen quite often when removing a device, perhaps shutdown() gets 
      // called twice?
//      throw new IllegalStateException("I didn't know about device "
//          + device.getId() + "!");      
    }
  }


  /**
   * <p>First line starts "List of devices"
   *
   * <p>When an emulator is started with a debug port, then it shows up
   * in the list of devices.
   *
   * <p>List of devices attached
   * <br>HT91MLC00031 device
   * <br>emulator-5554 offline
   *
   * <p>List of devices attached
   * <br>HT91MLC00031 device
   * <br>emulator-5554 device
   *
   * @return list of device identifiers
   * @throws IOException
   */
  public List<String> list() {
    if (AndroidSDK.adbDisabled) {
      return Collections.emptyList();
    }
    
    ProcessResult result;
    try {
//      System.out.println("listing devices 00");
      result = sdk.runADB("devices");
//      System.out.println("listing devices 05");
    } catch (InterruptedException e) {
      return Collections.emptyList();
    } catch (IOException e) {
      System.err.println("Problem inside Devices.list()");
      e.printStackTrace();
//      System.err.println(e);
//      System.err.println("checking devices");
//      e.printStackTrace(EditorConsole.systemErr);
      return Collections.emptyList();
    }
//    System.out.println("listing devices 10");
    if (!result.succeeded()) {
      if (result.getStderr().contains("protocol fault (no status)")) {
        System.err.println("bleh: " + result);  // THIS IS WORKING
      } else {
        System.err.println("nope: " + result);
      }
      return Collections.emptyList();
    }
//    System.out.println("listing devices 20");

    // might read "List of devices attached"
    final String stdout = result.getStdout();
    if (!(stdout.contains("List of devices") || stdout.trim().length() == 0)) {
      System.err.println(AndroidMode.getTextString("android_devices.error.cannot_get_device_list"));
      System.err.println(stdout);
      return Collections.emptyList();
    }

//    System.out.println("listing devices 30");
    final List<String> devices = new ArrayList<String>();
    for (final String line : result) {
      if (line.contains("\t")) {
        final String[] fields = line.split("\t");
        if (fields[1].equals("device")) {
          devices.add(fields[0]);
        } else if (fields[1].contains("no permissions") && showPermissionsErrorMessage) {
          AndroidUtil.showMessage(AndroidMode.getTextString("android_devices.error.no_permissions_title"),
                                  AndroidMode.getTextString("android_devices.error.no_permissions_body", DEVICE_PERMISSIONS_URL));
          showPermissionsErrorMessage = false;
        }
      }
    }
    return devices;
  }
}
