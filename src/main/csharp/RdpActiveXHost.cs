using System;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using System.Runtime.InteropServices;
using System.Drawing;

sealed class RdpConfiguration
{
    public string Server;
    public string UserName;
    public string Password;
    public string Name;
    public int Port;
    public int Width;
    public int Height;

    public static RdpConfiguration ReadFromStandardInput()
    {
        return new RdpConfiguration
        {
            Server = ReadValue(),
            UserName = ReadValue(),
            Password = ReadValue(),
            Name = ReadValue(),
            Port = Int32.Parse(ReadValue()),
            Width = Int32.Parse(ReadValue()),
            Height = Int32.Parse(ReadValue())
        };
    }

    static string ReadValue()
    {
        var value = Console.ReadLine();
        if (value == null)
        {
            throw new EndOfStreamException("The RDP host configuration is incomplete.");
        }
        return Encoding.UTF8.GetString(Convert.FromBase64String(value));
    }
}

[ComImport]
[Guid("336D5562-EFA8-482E-8CB3-C5C0FC7A7DB6")]
[InterfaceType(ComInterfaceType.InterfaceIsIDispatch)]
interface IMsTscAxEventsSink
{
    [DispId(1)]
    void OnConnecting();

    [DispId(2)]
    void OnConnected();

    [DispId(3)]
    void OnLoginComplete();

    [DispId(4)]
    void OnDisconnected(int disconnectReason);

    [DispId(10)]
    void OnFatalError(int errorCode);

    [DispId(11)]
    void OnWarning(int warningCode);

    [DispId(22)]
    void OnLogonError(int errorCode);
}

sealed class RdpEventSink : IMsTscAxEventsSink
{
    readonly Action<int> disconnected;
    readonly Action loginComplete;
    readonly Action<int> fatalError;
    readonly Action<int> logonError;

    public RdpEventSink(
        Action loginComplete,
        Action<int> disconnected,
        Action<int> fatalError,
        Action<int> logonError
    )
    {
        this.loginComplete = loginComplete;
        this.disconnected = disconnected;
        this.fatalError = fatalError;
        this.logonError = logonError;
    }

    public void OnConnecting()
    {
        RdpActiveXHost.WriteLine("STATE\tEVENT CONNECTING");
    }

    public void OnConnected()
    {
        RdpActiveXHost.WriteLine("STATE\tEVENT CONNECTED");
    }

    public void OnLoginComplete()
    {
        RdpActiveXHost.WriteLine("STATE\tEVENT LOGIN_COMPLETE");
        loginComplete();
    }

    public void OnDisconnected(int disconnectReason)
    {
        disconnected(disconnectReason);
    }

    public void OnFatalError(int errorCode)
    {
        RdpActiveXHost.WriteLine("STATE\tEVENT FATAL_ERROR code=" + errorCode);
        fatalError(errorCode);
    }

    public void OnWarning(int warningCode)
    {
        RdpActiveXHost.WriteLine("STATE\tEVENT WARNING code=" + warningCode);
    }

    public void OnLogonError(int errorCode)
    {
        RdpActiveXHost.WriteLine(
            "STATE\tEVENT LOGON_ERROR code=" + errorCode +
            " meaning=" + DescribeLogonError(errorCode)
        );
        logonError(errorCode);
    }

    public static string DescribeLogonError(int errorCode)
    {
        switch (errorCode)
        {
            case 0:
                return "BAD_PASSWORD";
            case 1:
                return "PASSWORD_UPDATE_REQUIRED";
            case 2:
                return "OTHER";
            case 3:
                return "WARNING";
            case -1:
                return "ACCESS_DENIED";
            case unchecked((int)0xC000006D):
                return "STATUS_LOGON_FAILURE";
            case unchecked((int)0xC000006E):
                return "STATUS_ACCOUNT_RESTRICTION";
            case unchecked((int)0xC0000224):
                return "STATUS_PASSWORD_MUST_CHANGE";
            default:
                return "UNKNOWN";
        }
    }
}

sealed class RdpAxHost : AxHost
{
    // MsRdpClient11NotSafeForScripting, registered as RDP client version 12.
    // This desktop-container class supports programmatic credential injection.
    const string ClassId = "1DF7C823-B2D4-4B54-975A-F2AC5D7CF8B8";

    ConnectionPointCookie eventCookie;
    RdpEventSink eventSink;
    public Action LoginComplete;
    public Action<int> Disconnected;
    public Action<int> FatalError;
    public Action<int> LogonError;

    public RdpAxHost() : base(ClassId)
    {
    }

    public dynamic Client
    {
        get { return GetOcx(); }
    }

    public void EnsureEventSink()
    {
        if (eventCookie == null)
        {
            CreateSink();
        }
    }

    protected override void CreateSink()
    {
        if (eventCookie != null)
        {
            return;
        }
        base.CreateSink();
        eventSink = new RdpEventSink(
            delegate
            {
                if (LoginComplete != null)
                {
                    LoginComplete();
                }
            },
            delegate(int reason)
            {
                if (Disconnected != null)
                {
                    Disconnected(reason);
                }
                else
                {
                    RdpActiveXHost.WriteLine(
                        "STATE\tEVENT DISCONNECTED reason=" + reason
                    );
                }
            },
            delegate(int errorCode)
            {
                if (FatalError != null)
                {
                    FatalError(errorCode);
                }
            },
            delegate(int errorCode)
            {
                if (LogonError != null)
                {
                    LogonError(errorCode);
                }
            }
        );
        eventCookie = new ConnectionPointCookie(
            GetOcx(),
            eventSink,
            typeof(IMsTscAxEventsSink)
        );
        RdpActiveXHost.WriteLine("STATE\tEVENT_SINK_ATTACHED");
    }

    protected override void DetachSink()
    {
        if (eventCookie != null)
        {
            eventCookie.Disconnect();
            eventCookie = null;
            RdpActiveXHost.WriteLine("STATE\tEVENT_SINK_DETACHED");
        }
        eventSink = null;
        base.DetachSink();
    }
}
static class RdpComInterop
{
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    delegate int PutClearTextPasswordDelegate(
        IntPtr instance,
        [MarshalAs(UnmanagedType.BStr)] string password
    );

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    delegate int PutVariantBoolDelegate(IntPtr instance, short value);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    delegate int GetVariantBoolDelegate(IntPtr instance, out short value);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    delegate int PutExtendedPropertyDelegate(
        IntPtr instance,
        [MarshalAs(UnmanagedType.BStr)] string propertyName,
        IntPtr value
    );

    public static void SetClearTextPassword(object client, string password)
    {
        var interfaceId = new Guid("C1E6743A-41C1-4A74-832A-0DD06C1C7A0E");
        var unknown = Marshal.GetIUnknownForObject(client);
        IntPtr nonScriptable = IntPtr.Zero;
        try
        {
            Marshal.ThrowExceptionForHR(
                Marshal.QueryInterface(unknown, ref interfaceId, out nonScriptable)
            );
            var vtable = Marshal.ReadIntPtr(nonScriptable);
            var setterPointer = Marshal.ReadIntPtr(vtable, 3 * IntPtr.Size);
            var setter =
                (PutClearTextPasswordDelegate)Marshal.GetDelegateForFunctionPointer(
                    setterPointer,
                    typeof(PutClearTextPasswordDelegate)
                );
            Marshal.ThrowExceptionForHR(setter(nonScriptable, password));
        }
        finally
        {
            ReleaseInterface(nonScriptable);
            Marshal.Release(unknown);
        }
    }

    public static void SetBooleanProperty(
        object client,
        string interfaceIdText,
        int setterVtableSlot,
        int getterVtableSlot,
        bool value,
        string propertyName
    )
    {
        var interfaceId = new Guid(interfaceIdText);
        var unknown = Marshal.GetIUnknownForObject(client);
        IntPtr targetInterface = IntPtr.Zero;
        try
        {
            Marshal.ThrowExceptionForHR(
                Marshal.QueryInterface(unknown, ref interfaceId, out targetInterface)
            );
            var vtable = Marshal.ReadIntPtr(targetInterface);
            var setterPointer = Marshal.ReadIntPtr(
                vtable,
                setterVtableSlot * IntPtr.Size
            );
            var setter = (PutVariantBoolDelegate)
                Marshal.GetDelegateForFunctionPointer(
                    setterPointer,
                    typeof(PutVariantBoolDelegate)
                );
            Marshal.ThrowExceptionForHR(
                setter(targetInterface, value ? (short)-1 : (short)0)
            );

            var getterPointer = Marshal.ReadIntPtr(
                vtable,
                getterVtableSlot * IntPtr.Size
            );
            var getter = (GetVariantBoolDelegate)
                Marshal.GetDelegateForFunctionPointer(
                    getterPointer,
                    typeof(GetVariantBoolDelegate)
                );
            short actualValue;
            Marshal.ThrowExceptionForHR(
                getter(targetInterface, out actualValue)
            );
            RdpActiveXHost.WriteLine(
                "STATE\tCREDENTIAL_POLICY " + propertyName + "=" +
                (actualValue != 0)
            );
        }
        finally
        {
            ReleaseInterface(targetInterface);
            Marshal.Release(unknown);
        }
    }

    public static void SetExtendedUnsignedProperty(
        object client,
        string propertyName,
        uint value
    )
    {
        var interfaceId = new Guid("302D8188-0052-4807-806A-362B628F9AC5");
        var unknown = Marshal.GetIUnknownForObject(client);
        IntPtr extendedSettings = IntPtr.Zero;
        IntPtr variant = IntPtr.Zero;
        try
        {
            Marshal.ThrowExceptionForHR(
                Marshal.QueryInterface(unknown, ref interfaceId, out extendedSettings)
            );
            var vtable = Marshal.ReadIntPtr(extendedSettings);
            var setterPointer = Marshal.ReadIntPtr(vtable, 3 * IntPtr.Size);
            var setter = (PutExtendedPropertyDelegate)
                Marshal.GetDelegateForFunctionPointer(
                    setterPointer,
                    typeof(PutExtendedPropertyDelegate)
                );
            variant = Marshal.AllocCoTaskMem(16);
            Marshal.GetNativeVariantForObject(value, variant);
            Marshal.ThrowExceptionForHR(
                setter(extendedSettings, propertyName, variant)
            );
        }
        finally
        {
            if (variant != IntPtr.Zero)
            {
                VariantClear(variant);
                Marshal.FreeCoTaskMem(variant);
            }
            ReleaseInterface(extendedSettings);
            Marshal.Release(unknown);
        }
    }
    static void ReleaseInterface(IntPtr instance)
    {
        if (instance != IntPtr.Zero)
        {
            Marshal.Release(instance);
        }
    }

    [DllImport("oleaut32.dll")]
    static extern int VariantClear(IntPtr variant);
}

sealed class RdpDisplayController : IDisposable
{
    const int MinimumDimension = 200;
    const int MaximumDimension = 8192;
    const int ResizeDelayMilliseconds = 300;

    readonly Form form;
    readonly Control activeXHost;
    readonly dynamic client;
    readonly System.Windows.Forms.Timer resizeTimer;
    Size pendingSize;
    Size lastSessionSize;
    bool connectStarted;
    bool loginComplete;
    bool disposed;

    public RdpDisplayController(Form form, Control activeXHost, object client)
    {
        this.form = form;
        this.activeXHost = activeXHost;
        this.client = client;
        resizeTimer = new System.Windows.Forms.Timer
        {
            Interval = ResizeDelayMilliseconds
        };
        resizeTimer.Tick += HandleResizeTimer;
    }

    public void ResizeViewport(int width, int height)
    {
        if (disposed)
        {
            return;
        }

        pendingSize = NormalizeSize(width, height);
        ResizeControls(pendingSize);

        if (!connectStarted)
        {
            ConfigureInitialSession(pendingSize);
            return;
        }

        ScheduleSessionResize(
            loginComplete ? "viewport_changed" : "login_incomplete"
        );
    }

    public void OnConnectStarted()
    {
        connectStarted = true;
        RdpActiveXHost.WriteLine(
            "STATE\tDISPLAY_CONNECT_STARTED pendingSize=" +
            pendingSize.Width + "x" + pendingSize.Height
        );
    }

    public void OnLoginComplete()
    {
        loginComplete = true;
        ScheduleSessionResize("login_complete");
    }

    void ConfigureInitialSession(Size size)
    {
        client.DesktopWidth = size.Width;
        client.DesktopHeight = size.Height;
        ApplyDisplayScale(size);
        RdpActiveXHost.WriteLine(
            "STATE\tRESIZED_BEFORE_CONNECT size=" + size.Width + "x" +
            size.Height + " host=" + activeXHost.ClientSize.Width + "x" +
            activeXHost.ClientSize.Height
        );
    }

    void ResizeControls(Size size)
    {
        activeXHost.Dock = DockStyle.None;
        activeXHost.SetBounds(0, 0, size.Width, size.Height);
        activeXHost.Dock = DockStyle.Fill;
        form.PerformLayout();
    }

    void ScheduleSessionResize(string reason)
    {
        resizeTimer.Stop();
        if (!loginComplete)
        {
            RdpActiveXHost.WriteLine(
                "STATE\tSESSION_RESIZE_DEFERRED reason=" + reason +
                " size=" + pendingSize.Width + "x" + pendingSize.Height
            );
            return;
        }

        resizeTimer.Start();
        RdpActiveXHost.WriteLine(
            "STATE\tSESSION_RESIZE_SCHEDULED reason=" + reason +
            " size=" + pendingSize.Width + "x" + pendingSize.Height +
            " delayMs=" + ResizeDelayMilliseconds
        );
    }

    void HandleResizeTimer(object sender, EventArgs e)
    {
        resizeTimer.Stop();
        if (disposed || !loginComplete || pendingSize.IsEmpty)
        {
            return;
        }
        if (pendingSize == lastSessionSize)
        {
            RdpActiveXHost.WriteLine(
                "STATE\tSESSION_RESIZE_SKIPPED reason=unchanged size=" +
                pendingSize.Width + "x" + pendingSize.Height
            );
            return;
        }

        ApplySessionResize(pendingSize);
    }

    void ApplySessionResize(Size size)
    {
        var settings = ReadDisplaySettings(size);
        try
        {
            client.UpdateSessionDisplaySettings(
                size.Width,
                size.Height,
                settings.PhysicalWidth,
                settings.PhysicalHeight,
                0,
                settings.DesktopScale,
                settings.DeviceScale
            );
            lastSessionSize = size;
            RdpActiveXHost.WriteLine(
                "STATE\tSESSION_RESIZED size=" + size.Width + "x" +
                size.Height + " dpi=" + settings.Dpi +
                " scale=" + settings.DesktopScale
            );
        }
        catch (Exception updateError)
        {
            try
            {
                var status = client.Reconnect(size.Width, size.Height);
                lastSessionSize = size;
                RdpActiveXHost.WriteLine(
                    "STATE\tSESSION_RECONNECTED size=" + size.Width + "x" +
                    size.Height + " status=" + status
                );
            }
            catch (Exception reconnectError)
            {
                RdpActiveXHost.WriteLine(
                    "STATE\tSESSION_RESIZE_FAILED update=" +
                    Sanitize(updateError.Message) + " reconnect=" +
                    Sanitize(reconnectError.Message)
                );
            }
        }
    }

    void ApplyDisplayScale(Size size)
    {
        var settings = ReadDisplaySettings(size);
        try
        {
            RdpComInterop.SetExtendedUnsignedProperty(
                client,
                "DesktopScaleFactor",
                settings.DesktopScale
            );
            RdpComInterop.SetExtendedUnsignedProperty(
                client,
                "DeviceScaleFactor",
                settings.DeviceScale
            );
            RdpActiveXHost.WriteLine(
                "STATE\tINITIAL_DISPLAY_SETTINGS size=" + size.Width + "x" +
                size.Height + " physicalMm=" + settings.PhysicalWidth + "x" +
                settings.PhysicalHeight + " dpi=" + settings.Dpi +
                " desktopScale=" + settings.DesktopScale +
                " deviceScale=" + settings.DeviceScale
            );
        }
        catch (Exception exception)
        {
            RdpActiveXHost.WriteLine(
                "STATE\tINITIAL_DISPLAY_SETTINGS_FAILED " +
                Sanitize(exception.Message)
            );
        }
    }

    DisplaySettings ReadDisplaySettings(Size size)
    {
        var dpi = Math.Max(96, (int)GetDpiForWindow(form.Handle));
        var desktopScale =
            (uint)Math.Max(100, Math.Min(500, dpi * 100 / 96));
        return new DisplaySettings
        {
            Dpi = dpi,
            PhysicalWidth =
                Math.Max(1, (int)Math.Round(size.Width * 25.4 / dpi)),
            PhysicalHeight =
                Math.Max(1, (int)Math.Round(size.Height * 25.4 / dpi)),
            DesktopScale = desktopScale,
            DeviceScale = desktopScale <= 125
                ? 100U
                : desktopScale <= 150 ? 140U : 180U
        };
    }

    static Size NormalizeSize(int width, int height)
    {
        return new Size(
            Math.Max(MinimumDimension, Math.Min(MaximumDimension, width)),
            Math.Max(MinimumDimension, Math.Min(MaximumDimension, height))
        );
    }

    static string Sanitize(string value)
    {
        return (value ?? String.Empty)
            .Replace('\r', ' ')
            .Replace('\n', ' ')
            .Replace('\t', ' ');
    }

    public void Dispose()
    {
        if (disposed)
        {
            return;
        }
        disposed = true;
        resizeTimer.Stop();
        resizeTimer.Tick -= HandleResizeTimer;
        resizeTimer.Dispose();
        RdpActiveXHost.WriteLine("STATE\tDISPLAY_CONTROLLER_RELEASED");
    }

    struct DisplaySettings
    {
        public int Dpi;
        public int PhysicalWidth;
        public int PhysicalHeight;
        public uint DesktopScale;
        public uint DeviceScale;
    }

    [DllImport("user32.dll")]
    static extern uint GetDpiForWindow(IntPtr window);
}

static class RdpIdentityConfigurator
{
    public static void Configure(
        dynamic client,
        string server,
        string configuredUserName
    )
    {
        var separator = configuredUserName.IndexOf('\\');
        if (separator > 0)
        {
            client.Domain = configuredUserName.Substring(0, separator);
            client.UserName = configuredUserName.Substring(separator + 1);
            RdpActiveXHost.WriteLine(
                "STATE\tIDENTITY_CONFIGURED mode=explicitDomain"
            );
            return;
        }

        if (configuredUserName.IndexOf('@') > 0)
        {
            client.Domain = String.Empty;
            client.UserName = configuredUserName;
            RdpActiveXHost.WriteLine(
                "STATE\tIDENTITY_CONFIGURED mode=upn"
            );
            return;
        }

        var targetComputerName = ResolveTargetComputerName(server);
        client.Domain = targetComputerName;
        client.UserName = configuredUserName;
        RdpActiveXHost.WriteLine(
            String.IsNullOrEmpty(targetComputerName)
                ? "STATE\tIDENTITY_CONFIGURED mode=bare resolution=unavailable"
                : "STATE\tIDENTITY_CONFIGURED mode=resolvedTarget target=" +
                    targetComputerName
        );
    }

    static string ResolveTargetComputerName(string server)
    {
        var normalizedServer = server.Trim().Trim('[', ']');
        IPAddress address;
        if (!IPAddress.TryParse(normalizedServer, out address))
        {
            var separator = normalizedServer.IndexOf('.');
            var computerName = separator > 0
                ? normalizedServer.Substring(0, separator)
                : normalizedServer;
            return IsValidComputerName(computerName)
                ? computerName.ToUpperInvariant()
                : String.Empty;
        }

        if (address.AddressFamily !=
            System.Net.Sockets.AddressFamily.InterNetwork)
        {
            return String.Empty;
        }
        return ResolveNetBiosComputerName(normalizedServer);
    }

    static string ResolveNetBiosComputerName(string ipv4Address)
    {
        try
        {
            var startInfo = new ProcessStartInfo
            {
                FileName = Path.Combine(
                    Environment.SystemDirectory,
                    "nbtstat.exe"
                ),
                Arguments = "-A " + ipv4Address,
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true
            };
            using (var process = Process.Start(startInfo))
            {
                if (process == null)
                {
                    return String.Empty;
                }
                if (!process.WaitForExit(2500))
                {
                    process.Kill();
                    return String.Empty;
                }

                var output = process.StandardOutput.ReadToEnd();
                var lines = output.Split(
                    new[] { '\r', '\n' },
                    StringSplitOptions.RemoveEmptyEntries
                );
                var computerName = FindNetBiosName(lines, "<20>");
                if (String.IsNullOrEmpty(computerName))
                {
                    computerName = FindNetBiosName(lines, "<00>");
                }
                return String.IsNullOrEmpty(computerName)
                    ? String.Empty
                    : computerName.ToUpperInvariant();
            }
        }
        catch
        {
            // NetBIOS can be unavailable. The RDP server then resolves a bare
            // user name according to its own account policy.
            return String.Empty;
        }
    }

    static string FindNetBiosName(string[] lines, string suffix)
    {
        foreach (var line in lines)
        {
            var suffixIndex = line.IndexOf(
                suffix,
                StringComparison.OrdinalIgnoreCase
            );
            if (suffixIndex <= 0)
            {
                continue;
            }

            var computerName = line.Substring(0, suffixIndex).Trim();
            if (IsValidComputerName(computerName))
            {
                return computerName;
            }
        }
        return String.Empty;
    }

    static bool IsValidComputerName(string value)
    {
        if (String.IsNullOrWhiteSpace(value) || value.Length > 63)
        {
            return false;
        }
        foreach (var character in value)
        {
            if (!Char.IsLetterOrDigit(character) && character != '-')
            {
                return false;
            }
        }
        return true;
    }
}

static class RdpFailureMessages
{
    public static bool IsLogonStatusOnly(int errorCode)
    {
        switch (errorCode)
        {
            case 3:
            case -2:
            case -3:
            case -4:
            case -5:
            case -6:
            case -7:
                return true;
            default:
                return false;
        }
    }

    public static string DescribeLogonFailure(int errorCode)
    {
        switch (errorCode)
        {
            case 0:
            case unchecked((int)0xC000006D):
                return "The user name or password is incorrect.";
            case 1:
            case unchecked((int)0xC0000224):
                return "The password has expired and must be changed before signing in.";
            case -1:
                return "Access was denied for this user account.";
            case unchecked((int)0xC000006E):
                return "This user account is restricted from signing in.";
            default:
                return "Remote Desktop login failed (" +
                    RdpEventSink.DescribeLogonError(errorCode) +
                    ", code " + errorCode + ").";
        }
    }

    public static string DescribeDisconnectReason(int disconnectReason)
    {
        switch (disconnectReason)
        {
            case 3:
                return "The remote computer ended the connection.";
            case 260:
            case 1288:
                return "The remote computer name could not be resolved.";
            case 264:
                return "The connection timed out.";
            case 772:
            case 2308:
                return "The network connection was closed.";
            case 2052:
                return "The remote computer address is invalid.";
            case 2055:
            case 7943:
                return "The user name or password is incorrect.";
            case 2567:
                return "The specified user account does not exist.";
            case 2823:
                return "The user account is disabled.";
            case 3079:
                return "The user account is restricted from signing in.";
            case 3335:
                return "The user account is locked.";
            case 3591:
                return "The user account has expired.";
            case 3847:
                return "The password has expired.";
            case 4615:
                return "The password must be changed before signing in.";
            case 5639:
            case 5895:
                return "The credential delegation policy blocked this connection.";
            case 6151:
                return "No authentication authority could be contacted.";
            case 6919:
                return "The remote computer certificate has expired.";
            default:
                return "The Remote Desktop connection ended unexpectedly.";
        }
    }

    public static bool IsAuthenticationFailure(int disconnectReason)
    {
        switch (disconnectReason)
        {
            case 2055:
            case 2567:
            case 2823:
            case 3079:
            case 3335:
            case 3591:
            case 3847:
            case 4615:
            case 5639:
            case 5895:
            case 6151:
            case 7943:
            case 8455:
                return true;
            default:
                return false;
        }
    }
}

sealed class RdpSessionForm : Form
{
    const int GwlHwndParent = -8;
    const uint GwOwner = 4;
    const uint SwpNoZOrder = 0x0004;
    const uint SwpNoActivate = 0x0010;
    const uint SwpShowWindow = 0x0040;
    const int SwHide = 0;

    readonly RdpAxHost host;
    readonly RdpDisplayController displayController;
    dynamic client;
    readonly System.Windows.Forms.Timer connectionStatusTimer;
    int connectionStatusTicks;
    int lastConnectedState = Int32.MinValue;
    bool closing;
    bool loginComplete;
    bool failureReported;
    bool resourcesReleased;
    bool overlayVisible;
    IntPtr overlayOwner;
    Rectangle overlayBounds = Rectangle.Empty;

    protected override bool ShowWithoutActivation
    {
        get { return true; }
    }

    public RdpSessionForm(RdpConfiguration configuration)
    {
        FormBorderStyle = FormBorderStyle.None;
        ShowInTaskbar = false;
        StartPosition = FormStartPosition.Manual;
        Left = -32000;
        Top = -32000;
        Width = configuration.Width;
        Height = configuration.Height;

        host = new RdpAxHost { Dock = DockStyle.Fill };
        Controls.Add(host);
        Show();
        host.CreateControl();
        client = host.Client;
        displayController = new RdpDisplayController(this, host, client);
        RdpActiveXHost.WriteLine(
            "STATE\tACTIVEX_CONTROL variant=notSafeForScripting version=12"
        );

        connectionStatusTimer = new System.Windows.Forms.Timer { Interval = 1000 };
        connectionStatusTimer.Tick += delegate
        {
            connectionStatusTicks++;
            LogConnectedState(connectionStatusTicks % 5 == 0);
        };
        host.LoginComplete = delegate
        {
            loginComplete = true;
            displayController.OnLoginComplete();
        };
        host.Disconnected = HandleDisconnected;
        host.FatalError = HandleFatalError;
        host.LogonError = HandleLogonError;
        host.EnsureEventSink();

        client.Server = configuration.Server;
        RdpIdentityConfigurator.Configure(
            client,
            configuration.Server,
            configuration.UserName
        );
        client.DesktopWidth = configuration.Width;
        client.DesktopHeight = configuration.Height;
        client.ColorDepth = 32;
        client.ConnectingText = "Connecting to " + configuration.Name + "...";

        var advancedSettings = client.AdvancedSettings2;
        advancedSettings.RDPPort = configuration.Port;
        advancedSettings.SmartSizing = false;
        advancedSettings.RedirectClipboard = true;
        advancedSettings.EnableWindowsKey = 1;
        advancedSettings.GrabFocusOnConnect = true;
        client.AdvancedSettings7.EnableCredSspSupport = true;
        client.SecuredSettings2.KeyboardHookMode = 1;
        RdpActiveXHost.WriteLine(
            "STATE\tKEYBOARD_POLICY hookMode=" +
            client.SecuredSettings2.KeyboardHookMode +
            " windowsKeyEnabled=" + advancedSettings.EnableWindowsKey
        );
        RdpActiveXHost.WriteLine(
            "STATE\tKEYBOARD_TRANSPORT mode=native_activex"
        );
        if (!String.IsNullOrEmpty(configuration.Password))
        {
            advancedSettings.ClearTextPassword = configuration.Password;
            RdpComInterop.SetClearTextPassword(client, configuration.Password);
            ConfigureCredentialPromptPolicy();
            RdpActiveXHost.WriteLine(
                "STATE\tPASSWORD_CONFIGURED channels=advancedSettings,nonScriptable"
            );
        }
        RdpActiveXHost.WriteLine(
            "STATE\tCONFIGURED passwordProvided=" +
            (!String.IsNullOrEmpty(configuration.Password)) +
            " initialSize=" + configuration.Width + "x" + configuration.Height
        );

        FormClosing += delegate
        {
            closing = true;
            connectionStatusTimer.Stop();
            RdpActiveXHost.WriteLine("STATE\tCLOSE_STARTED");
            try
            {
                if (client != null && client.Connected != 0)
                {
                    client.Disconnect();
                    RdpActiveXHost.WriteLine("STATE\tRDP_DISCONNECT_CALLED");
                }
            }
            catch (Exception exception)
            {
                RdpActiveXHost.WriteLine(
                    "STATE\tRDP_DISCONNECT_SKIPPED " + exception.Message
                );
            }
        };
        FormClosed += delegate
        {
            RdpActiveXHost.WriteLine("STATE\tFORM_CLOSED");
        };
    }

    protected override void Dispose(bool disposing)
    {
        var releaseResources = disposing && !resourcesReleased;
        if (releaseResources)
        {
            resourcesReleased = true;
            connectionStatusTimer.Stop();
            connectionStatusTimer.Dispose();
            displayController.Dispose();
            host.LoginComplete = null;
            host.Disconnected = null;
            host.FatalError = null;
            host.LogonError = null;
            client = null;
        }
        base.Dispose(disposing);
        if (releaseResources)
        {
            RdpActiveXHost.WriteLine("STATE\tRESOURCES_RELEASED");
        }
    }

    public void Connect()
    {
        displayController.OnConnectStarted();
        client.Connect();
        connectionStatusTicks = 0;
        LogConnectedState(true);
        connectionStatusTimer.Start();
    }

    public void FocusClient()
    {
        if (!overlayVisible)
        {
            return;
        }
        Activate();
        host.Select();
        host.Focus();
    }

    public void SetOverlayOwner(long ownerHandle)
    {
        var owner = new IntPtr(ownerHandle);
        if (owner == IntPtr.Zero || owner == overlayOwner)
        {
            return;
        }

        SetWindowOwner(Handle, owner);
        var actualOwner = GetWindow(Handle, GwOwner);
        if (actualOwner != owner)
        {
            throw new InvalidOperationException(
                "Unable to assign the Termora window as the RDP overlay owner."
            );
        }
        overlayOwner = owner;
        RdpActiveXHost.WriteLine(
            "STATE\tOVERLAY_OWNER_SET owner=" + owner.ToInt64()
        );
    }

    public void SetOverlayBounds(
        int left,
        int top,
        int width,
        int height,
        bool visible
    )
    {
        width = Math.Max(1, Math.Min(8192, width));
        height = Math.Max(1, Math.Min(8192, height));
        var bounds = new Rectangle(left, top, width, height);

        if (!visible)
        {
            if (overlayVisible)
            {
                ShowWindow(Handle, SwHide);
                overlayVisible = false;
                RdpActiveXHost.WriteLine("STATE\tOVERLAY_HIDDEN");
            }
            return;
        }

        if (bounds != overlayBounds || !overlayVisible)
        {
            if (!SetWindowPos(
                Handle,
                IntPtr.Zero,
                bounds.Left,
                bounds.Top,
                bounds.Width,
                bounds.Height,
                SwpNoZOrder | SwpNoActivate | SwpShowWindow
            ))
            {
                throw new InvalidOperationException(
                    "Unable to position the RDP overlay (Win32 error " +
                    Marshal.GetLastWin32Error() + ")."
                );
            }
            overlayBounds = bounds;
            overlayVisible = true;
            RdpActiveXHost.WriteLine(
                "STATE\tOVERLAY_BOUNDS left=" + bounds.Left +
                " top=" + bounds.Top + " size=" + bounds.Width + "x" +
                bounds.Height
            );
        }

        displayController.ResizeViewport(bounds.Width, bounds.Height);
    }

    [DllImport("user32.dll", EntryPoint = "SetWindowLongPtr", SetLastError = true)]
    static extern IntPtr SetWindowLongPtr64(
        IntPtr window,
        int index,
        IntPtr newValue
    );

    [DllImport("user32.dll", EntryPoint = "SetWindowLong", SetLastError = true)]
    static extern int SetWindowLong32(
        IntPtr window,
        int index,
        int newValue
    );

    [DllImport("user32.dll", SetLastError = true)]
    static extern bool SetWindowPos(
        IntPtr window,
        IntPtr insertAfter,
        int left,
        int top,
        int width,
        int height,
        uint flags
    );

    [DllImport("user32.dll")]
    static extern bool ShowWindow(IntPtr window, int command);

    [DllImport("user32.dll")]
    static extern IntPtr GetWindow(IntPtr window, uint command);

    static void SetWindowOwner(IntPtr window, IntPtr owner)
    {
        if (IntPtr.Size == 8)
        {
            SetWindowLongPtr64(window, GwlHwndParent, owner);
        }
        else
        {
            SetWindowLong32(window, GwlHwndParent, owner.ToInt32());
        }
        var error = Marshal.GetLastWin32Error();
        if (GetWindow(window, GwOwner) != owner && error != 0)
        {
            throw new InvalidOperationException(
                "Unable to set RDP overlay owner (Win32 error " + error + ")."
            );
        }
    }

    void HandleDisconnected(int disconnectReason)
    {
        connectionStatusTimer.Stop();
        var extendedReason = 0;
        var description = String.Empty;
        try
        {
            extendedReason = (int)client.ExtendedDisconnectReason;
            description = (string)client.GetErrorDescription(
                (uint)disconnectReason,
                (uint)extendedReason
            );
        }
        catch (Exception exception)
        {
            description = "description unavailable: " + exception.Message;
        }

        description = description
            .Replace('\r', ' ')
            .Replace('\n', ' ')
            .Replace('\t', ' ');
        RdpActiveXHost.WriteLine(
            "STATE\tEVENT DISCONNECTED reason=" + disconnectReason +
            " extendedReason=" + extendedReason +
            " description=" + description
        );

        if (closing)
        {
            return;
        }

        var fallback =
            RdpFailureMessages.DescribeDisconnectReason(disconnectReason);
        var detail = String.IsNullOrWhiteSpace(description)
            ? fallback
            : description;
        var kind = loginComplete
            ? "DISCONNECTED"
            : RdpFailureMessages.IsAuthenticationFailure(disconnectReason)
                ? "LOGIN"
                : "CONNECTION";
        ReportFailure(
            kind,
            detail + " (error " + disconnectReason +
            (extendedReason == 0 ? ")" : ", extended " + extendedReason + ")")
        );
    }

    void HandleFatalError(int errorCode)
    {
        ReportFailure(
            "FATAL",
            "The Remote Desktop client encountered a fatal error (code " +
            errorCode + ")."
        );
    }

    void HandleLogonError(int errorCode)
    {
        if (RdpFailureMessages.IsLogonStatusOnly(errorCode))
        {
            return;
        }
        ReportFailure(
            "LOGIN",
            RdpFailureMessages.DescribeLogonFailure(errorCode)
        );
    }

    void ReportFailure(string kind, string message)
    {
        if (closing || failureReported)
        {
            return;
        }
        failureReported = true;
        RdpActiveXHost.WriteFailure(kind, message);
    }

    void LogConnectedState(bool force)
    {
        try
        {
            var connectedState = (int)client.Connected;
            if (force || connectedState != lastConnectedState)
            {
                lastConnectedState = connectedState;
                RdpActiveXHost.WriteLine(
                    "STATE\tCONNECTION_STATUS connected=" + connectedState +
                    " elapsedSeconds=" + connectionStatusTicks
                );
            }
        }
        catch (Exception exception)
        {
            connectionStatusTimer.Stop();
            RdpActiveXHost.WriteLine(
                "STATE\tCONNECTION_STATUS_FAILED " + exception.Message
            );
        }
    }

    void ConfigureCredentialPromptPolicy()
    {
        RdpComInterop.SetBooleanProperty(
            client,
            "B3378D90-0728-45C7-8ED7-B6159FB92219",
            19,
            20,
            false,
            "PromptForCredentials"
        );
        RdpComInterop.SetBooleanProperty(
            client,
            "F50FA8AA-1C7D-4F59-B15C-A90CACAE1FCB",
            47,
            48,
            false,
            "PromptForCredsOnClient"
        );
        RdpComInterop.SetBooleanProperty(
            client,
            "4F6996D5-D7B1-412C-B0FF-063718566907",
            63,
            64,
            false,
            "AllowPromptingForCredentials"
        );
    }

}

static class RdpActiveXHost
{
    static readonly object OutputLock = new object();
    static readonly IntPtr DpiAwarenessContextPerMonitorAwareV2 = new IntPtr(-4);

    [DllImport("user32.dll")]
    static extern bool SetProcessDpiAwarenessContext(IntPtr dpiContext);

    public static void WriteLine(string line)
    {
        lock (OutputLock)
        {
            Console.WriteLine(line);
            Console.Out.Flush();
        }
    }

    static void WriteError(Exception exception)
    {
        var message = exception.GetType().FullName + ": " + exception.Message;
        WriteLine("ERROR\t" + Convert.ToBase64String(Encoding.UTF8.GetBytes(message)));
    }

    public static void WriteFailure(string kind, string message)
    {
        var encoded = Convert.ToBase64String(Encoding.UTF8.GetBytes(message));
        WriteLine("FAILURE\t" + kind + "\t" + encoded);
    }

    [STAThread]
    static int Main()
    {
        try
        {
            Console.OutputEncoding = new UTF8Encoding(false);
            var configuration = RdpConfiguration.ReadFromStandardInput();
            try
            {
                SetProcessDpiAwarenessContext(DpiAwarenessContextPerMonitorAwareV2);
            }
            catch (EntryPointNotFoundException)
            {
                // Windows versions before 10 1703 do not expose this API.
            }
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            using (var form = new RdpSessionForm(configuration))
            {
                var commandThread = new Thread(delegate()
                {
                    string command;
                    while ((command = Console.ReadLine()) != null)
                    {
                        if (!form.IsHandleCreated)
                        {
                            return;
                        }

                        if (String.Equals(command, "CONNECT", StringComparison.Ordinal))
                        {
                            form.BeginInvoke((Action)delegate
                            {
                                try
                                {
                                    WriteLine("STATE\tCONNECTING");
                                    form.Connect();
                                    form.FocusClient();
                                    WriteLine("STATE\tCONNECT_CALLED");
                                }
                                catch (Exception e)
                                {
                                    WriteFailure(
                                        "CONNECTION",
                                        e.GetType().FullName + ": " + e.Message
                                    );
                                    form.Close();
                                }
                            });
                        }
                        else if (String.Equals(command, "CLOSE", StringComparison.Ordinal))
                        {
                            form.BeginInvoke((Action)form.Close);
                            return;
                        }
                        else if (String.Equals(command, "FOCUS", StringComparison.Ordinal))
                        {
                            form.BeginInvoke((Action)form.FocusClient);
                        }
                        else if (command.StartsWith("OWNER\t", StringComparison.Ordinal))
                        {
                            long ownerHandle;
                            if (Int64.TryParse(
                                command.Substring("OWNER\t".Length),
                                out ownerHandle
                            ))
                            {
                                form.BeginInvoke((Action)delegate
                                {
                                    form.SetOverlayOwner(ownerHandle);
                                });
                            }
                        }
                        else if (command.StartsWith("BOUNDS\t", StringComparison.Ordinal))
                        {
                            var parts = command.Split('\t');
                            int left;
                            int top;
                            int width;
                            int height;
                            if (parts.Length == 6 &&
                                Int32.TryParse(parts[1], out left) &&
                                Int32.TryParse(parts[2], out top) &&
                                Int32.TryParse(parts[3], out width) &&
                                Int32.TryParse(parts[4], out height))
                            {
                                var visible = parts[5] == "1";
                                form.BeginInvoke((Action)delegate
                                {
                                    form.SetOverlayBounds(
                                        left,
                                        top,
                                        width,
                                        height,
                                        visible
                                    );
                                });
                            }
                        }
                    }
                    if (form.IsHandleCreated && !form.IsDisposed)
                    {
                        WriteLine("STATE\tCOMMAND_STREAM_CLOSED");
                        form.BeginInvoke((Action)form.Close);
                    }
                });
                commandThread.IsBackground = true;
                commandThread.Name = "Termora RDP command reader";
                commandThread.Start();

                // READY is emitted from the WinForms message loop so Termora
                // cannot send owner, bounds, or connect commands before this
                // native window is ready to process them.
                form.BeginInvoke((Action)delegate
                {
                    WriteLine("READY\t" + form.Handle.ToInt64());
                });
                Application.Run(new ApplicationContext(form));
            }
            WriteLine("STATE\tHOST_EXIT");
            return 0;
        }
        catch (Exception e)
        {
            WriteError(e);
            return 1;
        }
    }
}
