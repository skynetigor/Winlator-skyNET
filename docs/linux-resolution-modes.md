# Resolutions a Linux game can pick (RandR 1.3)

Status: written, builds, **not run on a device yet**.

## What it does

A Linux session used to have one screen size (the container's), and RandR 1.1 told a program only that. A game's own
resolution list therefore had one entry. Now the X server offers RandR 1.3 in Linux sessions: one screen, one output, one CRTC
and a list of modes, and setting a mode gives the screen that size. The Vulkan renderer scales the picture to the display as
it always did (letterbox bars when the proportions differ).

The modes are the container's own size and every standard size (640x360 up to 2560x1440, `STANDARD_SIZES` in
`RandrExtension`) that is not larger than it in either direction. The screen never grows past the container's size, so a
game can only go down from it and back up (a request for a bigger one is ignored, and the reported size range ends there).
Wine containers keep RandR 1.1 and one size (`setModesEnabled` is only called for Linux sessions).

## Where

* `xserver/extensions/RandrExtension.java`: the requests (GetScreenResources[Current], GetOutputInfo, GetCrtcInfo,
  SetCrtcConfig, SetScreenSize, GetScreenSizeRange, gamma, transform, panning, output properties as empty, SelectInput) and
  the events (RRScreenChangeNotify, RRNotify/CrtcChange) to clients that selected them. GetScreenInfo/SetScreenConfig (1.0
  style) list and set the sizes too.
* `xserver/XServer.resizeScreen`: sets `ScreenInfo` (now mutable), has `WindowManager.resizeRootWindow` replace the root
  window's picture and send ConfigureNotify, tells the view and the listeners.
* `widget/VulkanXServerView.onScreenResized` + `nativeSetScreenSize` (`VulkanRendererContext::setScreenSize`): the native
  renderer's scene size (it used to be fixed at start). `TouchpadView.onScreenResized`: touches map onto the new size.
* `XServerDisplayActivity`: turns the modes on for Linux sessions.

## To test

1. A game with a resolution list (BeamNG, Counter-Strike: Source): the list has many entries; picking one changes the picture
   size and the game keeps running; touches still land where they should.
2. `xrandr` from the runtime: lists modes, `xrandr --size 1280x720` works.
3. The container's own size first, then a smaller one and back; with a container of 800x600 the list has no size above it.
4. Wine containers: unchanged (RandR 1.1).
5. The EGL ("software output") renderer: its native code was not changed; it follows the root window's geometry like any
   other window, which may or may not be enough.

## Known gaps

* One refresh rate (60); modes have no rates of their own.
* No panning, rotation, several outputs or CRTCs.
* The EGL renderer is untested for a changing screen size.
