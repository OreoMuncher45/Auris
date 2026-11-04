// Takes the frame off the window Windows Subsystem for Android puts around an
// Android app.
//
// WSA hosts every Android app in a real Win32 window. That window arrives
// with a caption bar and a visible outline, which is the ugly border: the
// app draws its own chrome inside it, so the frame is in the way of nothing.
//
// What this does:
//   - removes WS_CAPTION, so the caption bar and its padding go;
//   - keeps WS_THICKFRAME, so the window is still resizable by its edges;
//   - asks DWM for an invisible border and rounded corners, the way a
//     Windows 11 app's own window looks.
//
// The window is found by the title WSA gives it, which is the Android app's
// name, and by the process WSA runs it in.

using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace Auris
{
    public static class WsaFrame
    {
        private const int GWL_STYLE = -16;
        private const int WS_CAPTION = 0x00C00000;
        private const int WS_THICKFRAME = 0x00040000;
        private const int WS_SYSMENU = 0x00080000;

        private const int SWP_NOSIZE = 0x0001;
        private const int SWP_NOMOVE = 0x0002;
        private const int SWP_NOZORDER = 0x0004;
        private const int SWP_NOACTIVATE = 0x0010;
        private const int SWP_FRAMECHANGED = 0x0020;

        private const int DWMWA_BORDER_COLOR = 34;
        private const int DWMWA_WINDOW_CORNER_PREFERENCE = 33;
        private const int DWMWCP_ROUND = 2;
        private const int DWMWA_COLOR_NONE = unchecked((int)0xFFFFFFFE);

        private delegate bool EnumWindowsProc(IntPtr handle, IntPtr parameter);

        [DllImport("user32.dll")]
        private static extern bool EnumWindows(EnumWindowsProc callback, IntPtr parameter);

        [DllImport("user32.dll")]
        private static extern bool IsWindowVisible(IntPtr handle);

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern int GetWindowText(IntPtr handle, StringBuilder text, int count);

        [DllImport("user32.dll")]
        private static extern int GetWindowTextLength(IntPtr handle);

        [DllImport("user32.dll")]
        private static extern uint GetWindowThreadProcessId(IntPtr handle, out uint processId);

        [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW")]
        private static extern IntPtr GetWindowLongPtr(IntPtr handle, int index);

        [DllImport("user32.dll", EntryPoint = "SetWindowLongPtrW")]
        private static extern IntPtr SetWindowLongPtr(IntPtr handle, int index, IntPtr value);

        [DllImport("user32.dll")]
        private static extern bool SetWindowPos(
            IntPtr handle, IntPtr insertAfter, int x, int y, int width, int height, uint flags);

        [DllImport("dwmapi.dll")]
        private static extern int DwmSetWindowAttribute(
            IntPtr handle, int attribute, ref int value, int size);

        /// <summary>Starts watching for the window and takes its frame off.</summary>
        public static void Start(string windowTitle)
        {
            var thread = new System.Threading.Thread(() => Watch(windowTitle));
            thread.IsBackground = true;
            thread.Start();
        }

        private static void Watch(string windowTitle)
        {
            var done = new System.Collections.Generic.HashSet<IntPtr>();
            while (true)
            {
                try
                {
                    EnumWindows(delegate(IntPtr handle, IntPtr parameter)
                    {
                        if (!IsWindowVisible(handle)) { return true; }
                        if (!IsWsaWindow(handle, windowTitle)) { return true; }
                        if (!done.Contains(handle))
                        {
                            if (Strip(handle))
                            {
                                done.Add(handle);
                            }
                        }
                        return true;
                    }, IntPtr.Zero);
                }
                catch
                {
                    // A window that went away between the two calls is not a
                    // failure; the next pass simply tries again.
                }
                System.Threading.Thread.Sleep(700);
            }
        }

        /// <summary>Whether this window is the one WSA is showing the app in.</summary>
        private static bool IsWsaWindow(IntPtr handle, string windowTitle)
        {
            var title = new StringBuilder(GetWindowTextLength(handle) + 2);
            GetWindowText(handle, title, title.Capacity);
            if (!string.IsNullOrWhiteSpace(windowTitle) &&
                title.ToString().IndexOf(windowTitle, StringComparison.OrdinalIgnoreCase) >= 0)
            {
                return true;
            }

            uint processId;
            GetWindowThreadProcessId(handle, out processId);
            if (processId == 0) { return false; }
            try
            {
                var name = Process.GetProcessById((int)processId).ProcessName;
                return name.StartsWith("WsaClient", StringComparison.OrdinalIgnoreCase) ||
                       name.StartsWith("WsaService", StringComparison.OrdinalIgnoreCase);
            }
            catch
            {
                return false;
            }
        }

        /// <summary>Takes the caption off and makes the border invisible.</summary>
        private static bool Strip(IntPtr handle)
        {
            try
            {
                var style = GetWindowLongPtr(handle, GWL_STYLE).ToInt64();
                var wanted = style & ~((long)WS_CAPTION | (long)WS_SYSMENU);
                if (wanted != style)
                {
                    SetWindowLongPtr(handle, GWL_STYLE, new IntPtr(wanted));
                }

                // An invisible border, and the rounded corners Windows 11 gives
                // its own windows. On an older build these are simply ignored.
                int none = DWMWA_COLOR_NONE;
                DwmSetWindowAttribute(handle, DWMWA_BORDER_COLOR, ref none, sizeof(int));
                int corner = DWMWCP_ROUND;
                DwmSetWindowAttribute(handle, DWMWA_WINDOW_CORNER_PREFERENCE, ref corner, sizeof(int));

                SetWindowPos(handle, IntPtr.Zero, 0, 0, 0, 0,
                    SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
                return true;
            }
            catch
            {
                return false;
            }
        }
    }
}
