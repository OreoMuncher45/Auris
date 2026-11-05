using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net.Sockets;
using System.Reflection;
using System.Text;
using System.Threading;

namespace AurisWsa;

/// <summary>
/// Auris on Windows.
///
/// The phone's app is an Android app, so it runs on Windows through the Windows Subsystem for
/// Android. This executable carries the APK inside it, finds WSA, installs the APK into it over
/// WSA's adb bridge and starts the app. One file, no installer, no separate downloads.
/// </summary>
internal static class Program
{
    /// <summary>Where the extracted pieces live between runs.</summary>
    private static readonly string WorkDir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Auris");

    private static readonly string LogFile = Path.Combine(WorkDir, "launcher.log");

    /// <summary>The packages the app has shipped under; the build decides which one is inside.</summary>
    private static readonly string[] CandidatePackages = { "com.music.bitchord", "com.dev.bitchord" };

    /// <summary>The WSA app package, as Windows knows it.</summary>
    private const string WsaPackageFamily = "MicrosoftCorporationII.WindowsSubsystemForAndroid_8wekyb3d8bbwe";

    private static int Main(string[] args)
    {
        Directory.CreateDirectory(WorkDir);
        Say("Auris — the phone's app, on Windows, through WSA.");
        Say("");

        try
        {
            var apk = ExtractResource("auris.apk", Path.Combine(WorkDir, "auris.apk"));
            var adb = ExtractResource("adb.exe", Path.Combine(WorkDir, "adb.exe"));
            ExtractResource("Auris.ico", Path.Combine(WorkDir, "Auris.ico"));

            if (!WsaInstalled())
            {
                Say("Windows Subsystem for Android is not installed on this machine.");
                Say("Install it, then run this again. WSA-Pacman does the whole job:");
                Say("  https://github.com/alesimula/wsa_pacman");
                Say("(it can install the APK itself too, if you would rather not run this).");
                OpenUrl("https://github.com/alesimula/wsa_pacman");
                return 2;
            }

            EnsureWsaRunning();

            var serial = FindAdbDevice(adb);
            if (serial is null)
            {
                // The bridge is off, but if Auris is already in WSA it can still be started:
                // WSA's client does not need the bridge for that.
                var client = WsaClientPath();
                if (client is not null)
                {
                    foreach (var package in CandidatePackages)
                    {
                        Run(client, $"/launch wsa://{package}", 30_000, quiet: true);
                    }

                    Say("WSA is running, but its adb bridge is not answering.");
                    Say("Tried to start Auris anyway, through WSA. If it did not open:");
                    Say("  WSA → Settings → Developer → ADB bridge, on.");
                    Say("  (The Settings page shows the port; this launcher finds it by itself.)");
                    Say("  WSA-Pacman turns it on for you: https://github.com/alesimula/wsa_pacman");
                    return 3;
                }

                Say("WSA is running, but its adb bridge is not answering.");
                Say("Turn it on once: WSA → Settings → Developer → ADB bridge.");
                Say("(The Settings page shows the port it listens on; this launcher finds it by itself.)");
                Say("WSA-Pacman turns it on for you: https://github.com/alesimula/wsa_pacman");
                return 3;
            }

            Say($"Connected to WSA over {serial}.");

            var package = Install(adb, serial, apk);
            if (package is null)
            {
                Say("The APK would not install. The log above says why.");
                return 4;
            }

            Say($"Installed {package}.");
            Launch(adb, serial, package);
            Say("");
            Say("Auris is running on Windows.");

            MakeShortcut(package);
            return 0;
        }
        catch (Exception e)
        {
            Say("Something went wrong: " + e.Message);
            Log(e.ToString());
            return 1;
        }
    }

    /// <summary>Pulls a resource out of the executable, so it can be used as a file.</summary>
    private static string ExtractResource(string name, string target)
    {
        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(name)
            ?? throw new FileNotFoundException($"The executable is missing {name}.");

        var info = new FileInfo(target);
        if (info.Exists && info.Length == stream.Length)
        {
            return target;
        }

        using var file = File.Create(target);
        stream.CopyTo(file);
        return target;
    }

    /// <summary>Whether WSA itself is installed.</summary>
    private static bool WsaInstalled()
    {
        var family = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        var packages = Path.Combine(family, "Packages");
        if (!Directory.Exists(packages))
        {
            return false;
        }

        return Directory.EnumerateDirectories(packages).Any(d =>
            Path.GetFileName(d).StartsWith("MicrosoftCorporationII.WindowsSubsystemForAndroid", StringComparison.OrdinalIgnoreCase));
    }

    /// <summary>Starts WSA if it is installed but not running, and waits for it.</summary>
    private static void EnsureWsaRunning()
    {
        if (Directory.Exists(@"\\wsa$\"))
        {
            Say("WSA is running.");
            return;
        }

        Say("WSA is installed but asleep. Starting it...");
        Run("explorer.exe", $"shell:AppsFolder\\{WsaPackageFamily}!App", 15_000);

        for (var i = 0; i < 60; i++)
        {
            if (Directory.Exists(@"\\wsa$\"))
            {
                Say("WSA is up.");
                return;
            }

            Thread.Sleep(1000);
        }

        Say("WSA did not come up within a minute; carrying on and trying anyway.");
    }

    /// <summary>
    /// Finds the adb bridge. WSA picks its own port and shows it in the Settings page, so the
    /// port is looked for rather than assumed: the ones WSA has used are tried first, then a
    /// short scan of the range around them.
    /// </summary>
    private static string? FindAdbDevice(string adb)
    {
        var candidates = new List<int> { 5858, 58526, 5555, 5554, 5037 };
        candidates.AddRange(Enumerable.Range(58500, 120));

        foreach (var port in candidates.Distinct())
        {
            if (!PortOpen(port))
            {
                continue;
            }

            Run(adb, $"connect 127.0.0.1:{port}", 15_000, quiet: true);

            var devices = Run(adb, "devices", 15_000, quiet: true);
            var serial = devices
                .Split('\n')
                .Select(line => line.Trim())
                .Where(line => line.StartsWith("127.0.0.1:", StringComparison.Ordinal))
                .Where(line => line.EndsWith("\tdevice", StringComparison.Ordinal))
                .Select(line => line.Split('\t')[0])
                .FirstOrDefault();

            if (serial is not null)
            {
                return serial;
            }
        }

        return null;
    }

    /// <summary>Whether something is listening on that loopback port.</summary>
    private static bool PortOpen(int port)
    {
        try
        {
            using var client = new TcpClient();
            var connect = client.BeginConnect("127.0.0.1", port, null, null);
            if (!connect.AsyncWaitHandle.WaitOne(250))
            {
                return false;
            }

            client.EndConnect(connect);
            return true;
        }
        catch
        {
            return false;
        }
    }

    /// <summary>Installs the APK, replacing an older copy when the signatures differ.</summary>
    private static string? Install(string adb, string serial, string apk)
    {
        Say("Installing the app...");
        var output = Run(adb, $"-s {serial} install -r -g -d \"{apk}\"", 300_000);

        if (output.Contains("Success", StringComparison.OrdinalIgnoreCase))
        {
            return FindInstalledPackage(adb, serial);
        }

        // A different key signed the copy already in WSA; the old one has to go first.
        if (output.Contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", StringComparison.OrdinalIgnoreCase)
            || output.Contains("INSTALL_FAILED_VERSION_DOWNGRADE", StringComparison.OrdinalIgnoreCase))
        {
            Say("An older copy signed with another key is in the way. Removing it and going again...");
            foreach (var package in CandidatePackages)
            {
                Run(adb, $"-s {serial} uninstall {package}", 60_000, quiet: true);
            }

            output = Run(adb, $"-s {serial} install -g -d \"{apk}\"", 300_000);
            if (output.Contains("Success", StringComparison.OrdinalIgnoreCase))
            {
                return FindInstalledPackage(adb, serial);
            }
        }

        Say(output);
        return null;
    }

    /// <summary>Which of the app's package names is the one that just landed in WSA.</summary>
    private static string? FindInstalledPackage(string adb, string serial)
    {
        var list = Run(adb, $"-s {serial} shell pm list packages", 30_000, quiet: true);
        var installed = CandidatePackages
            .Where(package => list.Contains($"package:{package}", StringComparison.Ordinal))
            .ToList();

        return installed.Count > 0 ? installed[0] : null;
    }

    /// <summary>Starts the app the way the launcher icon does.</summary>
    private static void Launch(string adb, string serial, string package)
    {
        Say("Starting Auris...");

        // WSA's own client starts the app without needing the adb bridge, which is how the
        // Start-menu entry WSA creates works. It is the better path when it is there.
        var client = WsaClientPath();
        if (client is not null)
        {
            Run(client, $"/launch wsa://{package}", 30_000);
            return;
        }

        Run(adb, $"-s {serial} shell monkey -p {package} -c android.intent.category.LAUNCHER 1", 60_000);
    }

    /// <summary>WSA's client, which is what launches an Android app from Windows.</summary>
    private static string? WsaClientPath()
    {
        var path = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Microsoft", "WindowsApps", WsaPackageFamily, "WsaClient.exe");

        return File.Exists(path) ? path : null;
    }

    /// <summary>Puts a shortcut in the Start menu, so the next run is one click.</summary>
    private static void MakeShortcut(string package)
    {
        try
        {
            var source = Environment.ProcessPath;
            if (source is null)
            {
                return;
            }

            // The launcher keeps a copy of itself, so it can be run again to update the app.
            var target = Path.Combine(WorkDir, "Auris.exe");
            if (!string.Equals(source, target, StringComparison.OrdinalIgnoreCase))
            {
                File.Copy(source, target, overwrite: true);
            }

            var programs = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "Microsoft", "Windows", "Start Menu", "Programs");
            Directory.CreateDirectory(programs);

            var type = Type.GetTypeFromProgID("WScript.Shell");
            if (type is null)
            {
                return;
            }

            dynamic shell = Activator.CreateInstance(type)!;
            dynamic shortcut = shell.CreateShortcut(Path.Combine(programs, "Auris.lnk"));
            shortcut.TargetPath = target;
            shortcut.WorkingDirectory = WorkDir;
            shortcut.IconLocation = IconFor(package);
            shortcut.Description = "Auris";
            shortcut.Save();
            Say("A shortcut is in the Start menu.");
        }
        catch (Exception e)
        {
            Log("shortcut: " + e.Message);
        }
    }

    /// <summary>
    /// The icon WSA writes for an installed app, when it has written one; the one carried
    /// inside this executable otherwise.
    /// </summary>
    private static string IconFor(string package)
    {
        var written = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Packages", WsaPackageFamily, "LocalState", package + ".ico");

        return File.Exists(written) ? written + ",0" : Path.Combine(WorkDir, "Auris.ico") + ",0";
    }

    /// <summary>Runs a program and returns what it printed.</summary>
    private static string Run(string file, string arguments, int timeoutMs, bool quiet = false)
    {
        var info = new ProcessStartInfo(file, arguments)
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };

        try
        {
            using var process = Process.Start(info);
            if (process is null)
            {
                return "";
            }

            var output = process.StandardOutput.ReadToEnd() + process.StandardError.ReadToEnd();
            if (!process.WaitForExit(timeoutMs))
            {
                try
                {
                    process.Kill(true);
                }
                catch
                {
                    // It was already gone.
                }
            }

            if (!quiet)
            {
                Log($"{file} {arguments}\n{output}");
            }

            return output;
        }
        catch (Exception e)
        {
            if (!quiet)
            {
                Say($"{file} failed: {e.Message}");
            }

            return "";
        }
    }

    private static void OpenUrl(string url)
    {
        try
        {
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
        }
        catch
        {
            // A browser that refuses to open is not worth a crash.
        }
    }

    private static void Say(string line)
    {
        Console.WriteLine(line);
        Log(line);
    }

    private static void Log(string line)
    {
        try
        {
            File.AppendAllText(LogFile, $"[{DateTime.Now:HH:mm:ss}] {line}{Environment.NewLine}");
        }
        catch
        {
            // A log that cannot be written is not worth a crash.
        }
    }
}
