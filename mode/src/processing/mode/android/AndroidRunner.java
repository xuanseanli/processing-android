/* -*- mode: java; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
 Part of the Processing project - http://processing.org

 Copyright (c) 2012-17 The Processing Foundation
 Copyright (c) 2011-12 Ben Fry and Casey Reas

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

import java.io.IOException;
import java.io.PrintStream;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.jdi.VirtualMachine;
import com.sun.jdi.VirtualMachineManager;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import processing.app.ui.Editor;
import processing.app.Messages;
import processing.app.RunnerListener;
import processing.app.SketchException;
import processing.mode.java.runner.Runner;

/** 
 * Launches an app on the device or in the emulator.
 */
public class AndroidRunner implements DeviceListener {
  private static final String DEFAULT_PACKAGE_NAME = "processing.android";
  
  AndroidBuild build;
  RunnerListener listener;

  protected PrintStream sketchErr;
  protected PrintStream sketchOut;

  private VirtualMachine vm;

  private boolean isDebugEnabled;

  public AndroidRunner(AndroidBuild build, RunnerListener listener) {
    this.build = build;
    this.listener = listener;

    if (listener instanceof AndroidEditor){
      isDebugEnabled = ((AndroidEditor) listener).isDebuggerEnabled();
    }

    if (listener instanceof Editor) {
      Editor editor = (Editor) listener;
      sketchErr = editor.getConsole().getErr();
      sketchOut = editor.getConsole().getOut();
    } else {
      sketchErr = System.err;
      sketchOut = System.out;
    }
  }


  public boolean launch(Future<Device> deviceFuture, int comp, boolean emu) {
    String devStr = emu ? "emulator" : "device";
    listener.statusNotice(AndroidMode.getTextString("android_runner.status.waiting_for_device", devStr));
    
    final Device device = waitForDevice(deviceFuture, listener);
    if (device == null || !device.isAlive()) {
      listener.statusError(AndroidMode.getTextString("android_runner.status.lost_connection_with_device", devStr));
      // Reset the server, in case that's the problem. Sometimes when
      // launching the emulator times out, the device list refuses to update.
      final Devices devices = Devices.getInstance();
      devices.killAdbServer();
      return false;
    }
    
    if (comp == AndroidBuild.WATCHFACE && !device.hasFeature("watch")) {
      listener.statusError(AndroidMode.getTextString("android_runner.status.cannot_install_sketch"));
      Messages.showWarning(AndroidMode.getTextString("android_runner.warn.non_watch_device_title"), 
                           AndroidMode.getTextString("android_runner.warn.non_watch_device_body"));      
      return false;
    }
    
    if (comp != AndroidBuild.WATCHFACE && device.hasFeature("watch")) {
      listener.statusError(AndroidMode.getTextString("android_runner.status.cannot_install_sketch"));
      Messages.showWarning(AndroidMode.getTextString("android_runner.warn.watch_device_title"), 
                           AndroidMode.getTextString("android_runner.warn.watch_device_body"));      
      return false;
    }

    device.addListener(this);
    device.setPackageName(build.getPackageName());
    listener.statusNotice(AndroidMode.getTextString("android_runner.status.installing_sketch", device.getId()));
    // this stopped working with Android SDK tools revision 17
    if (!device.installApp(build, listener)) {
      listener.statusError(AndroidMode.getTextString("android_runner.status.lost_connection", devStr));
      final Devices devices = Devices.getInstance();
      devices.killAdbServer();  // see above
      return false;
    }

    boolean status = false;
    if (comp == AndroidBuild.WATCHFACE || comp == AndroidBuild.WALLPAPER) {
      if (startSketch(build, device)) {
        listener.statusNotice(AndroidMode.getTextString("android_runner.status.sketch_installed")
                              + (device.isEmulator() ? " " + AndroidMode.getTextString("android_runner.status.in_emulator") : " " + 
                                                             AndroidMode.getTextString("android_runner.status.on_device")) + ".");
        status = true;
      } else {
        listener.statusError(AndroidMode.getTextString("android_runner.status.cannot_install_sketch"));
      }
    } else {
      listener.statusNotice(AndroidMode.getTextString("android_runner.status.launching_sketch", device.getId()));
      if (startSketch(build, device)) {
        listener.statusNotice(AndroidMode.getTextString("android_runner.status.sketch_launched")
                              + (device.isEmulator() ? " " + AndroidMode.getTextString("android_runner.status.in_emulator") : " " + 
                                                             AndroidMode.getTextString("android_runner.status.on_device")) + ".");
        status = true;
      } else {
        listener.statusError(AndroidMode.getTextString("android_runner.status.cannot_launch_sketch"));
      }
    }

    // Start Debug if Debugger is enabled
    if (isDebugEnabled){
      ((AndroidEditor) listener).getDebugger()
        .startDebug(this, device);
    }

    listener.stopIndeterminate();
    lastRunDevice = device;
    return status;
  }

  public VirtualMachine connectVirtualMachine(int port) throws IOException {
    String strPort = Integer.toString(port);
    AttachingConnector connector = getConnector();
    try {
      vm = connect(connector, strPort);
      return vm;
    } catch (IllegalConnectorArgumentsException e) {
      throw new IllegalStateException(e);
    }
  }

  private AttachingConnector getConnector() {
    VirtualMachineManager vmManager = org.eclipse.jdi.Bootstrap.virtualMachineManager();
    for (Connector connector : vmManager.attachingConnectors()) {
      if ("com.sun.jdi.SocketAttach".equals(connector.name())) {
        return (AttachingConnector) connector;
      }
    }
    throw new IllegalStateException();
  }

  private VirtualMachine connect(
      AttachingConnector connector, String port) throws IllegalConnectorArgumentsException, IOException {
    Map<String, Connector.Argument> args = connector
        .defaultArguments();
    Connector.Argument pidArgument = args.get("port");
    if (pidArgument == null) {
      throw new IllegalStateException();
    }
    pidArgument.setValue(port);

    return connector.attach(args);
  }

  public VirtualMachine vm(){
    return vm;
  }

  private volatile Device lastRunDevice = null;


  // if user asks for 480x320, 320x480, 854x480 etc, then launch like that
  // though would need to query the emulator to see if it can do that

  private boolean startSketch(AndroidBuild build, final Device device) {
    final String packageName = build.getPackageName();
    try {
      if (device.launchApp(packageName, isDebugEnabled)) {
        return true;
      }
    } catch (final Exception e) {
      e.printStackTrace(System.err);
    }
    return false;
  }


  private Device waitForDevice(Future<Device> deviceFuture, RunnerListener listener) {
    for (int i = 0; i < 120; i++) {
      if (listener.isHalted()) {
        deviceFuture.cancel(true);
        return null;
      }
      try {
        return deviceFuture.get(1, TimeUnit.SECONDS);
      } catch (final InterruptedException e) {
        listener.statusError("Interrupted.");
        return null;
      } catch (final ExecutionException e) {
        listener.statusError(e);
        return null;
      } catch (final TimeoutException expected) {
      }
    }
    listener.statusError(AndroidMode.getTextString("android_runner.status.cancel_waiting_for_device"));
    return null;
  }


  private static final Pattern LOCATION =
    Pattern.compile("\\(([^:]+):(\\d+)\\)");
  private static final Pattern EXCEPTION_PARSER =
    Pattern.compile("^\\s*([a-z]+(?:\\.[a-z]+)+)(?:: .+)?$",
                    Pattern.CASE_INSENSITIVE);

  /**
   * Currently figures out the first relevant stack trace line
   * by looking for the telltale presence of "processing.android"
   * in the package. If the packaging for droid sketches changes,
   * this method will have to change too.
   */
  public void stackTrace(final List<String> trace) {
    final Iterator<String> frames = trace.iterator();
    final String exceptionLine = frames.next();

    final Matcher m = EXCEPTION_PARSER.matcher(exceptionLine);
    if (!m.matches()) {
      System.err.println(AndroidMode.getTextString("android_runner.error.cannot_parse_stacktrace"));
      System.err.println(exceptionLine);
      listener.statusError(AndroidMode.getTextString("android_runner.status.unknwon_exception"));
      return;
    }
    final String exceptionClass = m.group(1);
    Runner.handleCommonErrors(exceptionClass, exceptionLine, listener, sketchErr);

    while (frames.hasNext()) {
      final String line = frames.next();
      if (line.contains(DEFAULT_PACKAGE_NAME)) {
        final Matcher lm = LOCATION.matcher(line);
        if (lm.find()) {
          final String filename = lm.group(1);
          final int lineNumber = Integer.parseInt(lm.group(2)) - 1;
          final SketchException rex =
            build.placeException(exceptionLine, filename, lineNumber);
          listener.statusError(rex == null ? new SketchException(exceptionLine, false) : rex);
          return;
        }
      }
    }
  }


  // called by AndroidMode.handleStop()...
  public void close() {
    if (lastRunDevice != null) {
      lastRunDevice.bringLauncherToFront();
    }

    if (vm != null) {
      try {
        vm.exit(0);

      } catch (com.sun.jdi.VMDisconnectedException vmde) {
        // if the vm has disconnected on its own, ignore message
        //System.out.println("harmless disconnect " + vmde.getMessage());
        // TODO shouldn't need to do this, need to do more cleanup
      }
    }
  }


  // sketch stopped on the device
  public void sketchStopped() {
    listener.stopIndeterminate();
    listener.statusHalt();
  }
}
