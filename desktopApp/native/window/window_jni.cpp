#define _WIN32_WINNT 0x0A00
#include <jni.h>

#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <audioclient.h>
#include <dwmapi.h>
#include <functiondiscoverykeys_devpkey.h>
#include <mmdeviceapi.h>
#include <propvarutil.h>
#include <windowsx.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cwchar>
#include <cwctype>
#include <cstring>
#include <mutex>
#include <string>

namespace {

HWND g_window = nullptr;
WNDPROC g_original_proc = nullptr;

// Posted from Java to start a caption drag on the thread that owns the window; see frame_proc.
constexpr UINT WM_BITCHORD_DRAG = WM_APP + 0x42;
// SC_MOVE through the caption: the same move loop a real title bar starts.
constexpr WPARAM SC_DRAGMOVE = SC_MOVE | HTCAPTION;

struct FindContext {
    const wchar_t* title;
    HWND found;
};

BOOL CALLBACK find_own_window(HWND window, LPARAM param) {
    auto* context = reinterpret_cast<FindContext*>(param);
    DWORD pid = 0;
    GetWindowThreadProcessId(window, &pid);
    if (pid != GetCurrentProcessId() || !IsWindowVisible(window)) return TRUE;

    wchar_t buffer[256] = {};
    GetWindowTextW(window, buffer, 255);
    if (wcscmp(buffer, context->title) != 0) return TRUE;
    context->found = window;
    return FALSE;
}

HWND find_window(JNIEnv* env, jstring title) {
    if (g_window != nullptr && IsWindow(g_window)) return g_window;
    const jchar* chars = env->GetStringChars(title, nullptr);
    const jsize length = env->GetStringLength(title);
    std::wstring wanted(reinterpret_cast<const wchar_t*>(chars), static_cast<size_t>(length));
    env->ReleaseStringChars(title, chars);
    FindContext context{wanted.c_str(), nullptr};
    EnumWindows(find_own_window, reinterpret_cast<LPARAM>(&context));
    return context.found;
}

int resize_border(HWND window, bool horizontal) {
    const UINT dpi = GetDpiForWindow(window);
    const int frame = GetSystemMetricsForDpi(horizontal ? SM_CXFRAME : SM_CYFRAME, dpi);
    const int padding = GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi);
    return frame + padding;
}

LRESULT CALLBACK frame_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    if (message == WM_NCCALCSIZE && wparam == TRUE) {
        // Keep the native overlapped-window styles but give the full surface to Compose. DWM still
        // owns the outer frame, shadow and transitions; only the standard caption is removed.
        if (IsZoomed(window)) {
            // A frameless client normally expands to the maximized window rectangle, which can
            // extend a few pixels into a taskbar docked at the top of the monitor. Use the monitor
            // work area for the client portion so the first row is never hidden behind the taskbar.
            MONITORINFO monitor{sizeof(MONITORINFO)};
            const HMONITOR handle = MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST);
            if (handle != nullptr && GetMonitorInfoW(handle, &monitor) != FALSE) {
                auto* sizes = reinterpret_cast<NCCALCSIZE_PARAMS*>(lparam);
                sizes->rgrc[0] = monitor.rcWork;
            }
        }
        return 0;
    }

    if (message == WM_NCHITTEST && !IsZoomed(window)) {
        POINT point{GET_X_LPARAM(lparam), GET_Y_LPARAM(lparam)};
        RECT bounds{};
        GetWindowRect(window, &bounds);
        const int border_x = resize_border(window, true);
        const int border_y = resize_border(window, false);
        const bool left = point.x < bounds.left + border_x;
        const bool right = point.x >= bounds.right - border_x;
        const bool top = point.y < bounds.top + border_y;
        const bool bottom = point.y >= bounds.bottom - border_y;
        if (top && left) return HTTOPLEFT;
        if (top && right) return HTTOPRIGHT;
        if (bottom && left) return HTBOTTOMLEFT;
        if (bottom && right) return HTBOTTOMRIGHT;
        if (left) return HTLEFT;
        if (right) return HTRIGHT;
        if (top) return HTTOP;
        if (bottom) return HTBOTTOM;
    }

    if (message == WM_BITCHORD_DRAG) {
        // Windows moves the window itself from here, as it does for a system caption: DWM slides
        // the composed surface, so nothing is exposed to be filled with the class brush (the white
        // band an AWT setLocation per mouse event left along the edges), and Aero Snap and
        // drag-to-restore come with it. The press that asked for this may already be over; a move
        // loop started without the button down would follow the cursor until the next click.
        const int primary = GetSystemMetrics(SM_SWAPBUTTON) ? VK_RBUTTON : VK_LBUTTON;
        if ((GetAsyncKeyState(primary) & 0x8000) == 0) return 0;
        // AWT captured the mouse on the press; the move loop needs it back.
        ReleaseCapture();
        DefWindowProcW(window, WM_SYSCOMMAND, SC_DRAGMOVE, 0);
        // The loop ate the release. AWT, and Compose behind it, still hold the press that started
        // the drag and would take the next one for part of it: a double click on the caption
        // never became one, and the first click anywhere after a drag went missing. Hand the
        // release back where the pointer now is.
        POINT cursor{};
        if (GetCursorPos(&cursor) != FALSE && ScreenToClient(window, &cursor) != FALSE) {
            PostMessageW(window, WM_LBUTTONUP, 0, MAKELPARAM(cursor.x, cursor.y));
        }
        return 0;
    }

    WNDPROC original = g_original_proc;
    if (message == WM_NCDESTROY) {
        g_window = nullptr;
        g_original_proc = nullptr;
    }
    return original != nullptr
        ? CallWindowProcW(original, window, message, wparam, lparam)
        : DefWindowProcW(window, message, wparam, lparam);
}

bool install_frame(HWND window) {
    if (window == nullptr) return false;
    if (window == g_window && g_original_proc != nullptr) return true;

    SetLastError(0);
    auto previous = reinterpret_cast<WNDPROC>(
        SetWindowLongPtrW(window, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(frame_proc)));
    if (previous == nullptr && GetLastError() != 0) return false;

    g_window = window;
    g_original_proc = previous;

    LONG_PTR style = GetWindowLongPtrW(window, GWL_STYLE);
    style &= ~static_cast<LONG_PTR>(WS_POPUP);
    style |= WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_THICKFRAME |
        WS_MINIMIZEBOX | WS_MAXIMIZEBOX;
    SetWindowLongPtrW(window, GWL_STYLE, style);

    // Ask DWM to own the non-client rendering and Windows 11 corner policy. Unlike an AWT shape,
    // these do not clip the surface and are automatically disabled when maximized or snapped.
    const DWMNCRENDERINGPOLICY policy = DWMNCRP_ENABLED;
    DwmSetWindowAttribute(
        window, DWMWA_NCRENDERING_POLICY, &policy, sizeof(policy));
#ifndef DWMWA_WINDOW_CORNER_PREFERENCE
#define DWMWA_WINDOW_CORNER_PREFERENCE 33
#endif
    const int rounded = 2;  // DWMWCP_ROUND; kept numeric so older SDKs can still build the bridge.
    DwmSetWindowAttribute(
        window, static_cast<DWMWINDOWATTRIBUTE>(DWMWA_WINDOW_CORNER_PREFERENCE),
        &rounded, sizeof(rounded));

    MARGINS margins{1, 1, 1, 1};
    DwmExtendFrameIntoClientArea(window, &margins);
    SetWindowPos(
        window, nullptr, 0, 0, 0, 0,
        SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
    return true;
}

// Windows 11 22H2's system backdrop attribute and its values; numeric so older SDKs still build.
constexpr DWORD BACKDROP_ATTRIBUTE = 38;  // DWMWA_SYSTEMBACKDROP_TYPE
constexpr DWORD DARK_MODE_ATTRIBUTE = 20;  // DWMWA_USE_IMMERSIVE_DARK_MODE
constexpr int BACKDROP_NONE = 1;  // DWMSBT_NONE

/**
 * Puts DWM's own material behind the window: 2 Mica, 3 Acrylic, anything else none. The frame is
 * extended over the whole client so the material reaches everywhere Compose leaves transparent;
 * without a material it goes back to the one-pixel margin the frame needs for its shadow.
 *
 * Dark mode is what makes the material a dark tint rather than a light one. False when the system
 * has no backdrop attribute (before Windows 11 22H2), and the caller keeps the window opaque.
 */
bool set_backdrop(HWND window, int kind) {
    if (window == nullptr || !IsWindow(window)) return false;
    const BOOL dark = TRUE;
    DwmSetWindowAttribute(
        window, static_cast<DWMWINDOWATTRIBUTE>(DARK_MODE_ATTRIBUTE), &dark, sizeof(dark));
    const bool material = kind == 2 || kind == 3;
    const int type = material ? kind : BACKDROP_NONE;
    const HRESULT applied = DwmSetWindowAttribute(
        window, static_cast<DWMWINDOWATTRIBUTE>(BACKDROP_ATTRIBUTE), &type, sizeof(type));
    if (FAILED(applied)) return false;
    MARGINS margins = material ? MARGINS{-1, -1, -1, -1} : MARGINS{1, 1, 1, 1};
    DwmExtendFrameIntoClientArea(window, &margins);
    return true;
}

bool send_system_command(UINT command) {
    HWND window = g_window;
    return window != nullptr && IsWindow(window) &&
        PostMessageW(window, WM_SYSCOMMAND, command, 0) != 0;
}

// ---- Shared-mode Windows audio --------------------------------------------

std::mutex g_audio_mutex;
IAudioClient* g_audio_client = nullptr;
IAudioRenderClient* g_audio_render = nullptr;
UINT32 g_audio_buffer_frames = 0;
UINT32 g_audio_channels = 0;
UINT32 g_audio_bytes_per_sample = 0;
bool g_audio_float = false;
bool g_audio_started = false;
uint64_t g_audio_submitted_frames = 0;
uint64_t g_audio_played_offset = 0;
std::wstring g_audio_device_name;
std::wstring g_audio_error;

class ComScope {
public:
    ComScope() : result(CoInitializeEx(nullptr, COINIT_MULTITHREADED)) {}
    ~ComScope() {
        if (result == S_OK || result == S_FALSE) CoUninitialize();
    }
    bool ready() const { return SUCCEEDED(result) || result == RPC_E_CHANGED_MODE; }
    HRESULT result;
};

void set_audio_error(const wchar_t* operation, HRESULT result) {
    wchar_t code[32] = {};
    swprintf(code, 31, L"0x%08lX", static_cast<unsigned long>(result));
    g_audio_error = operation;
    g_audio_error += L" failed (";
    g_audio_error += code;
    g_audio_error += L")";
}

void release_audio_locked() {
    if (g_audio_client != nullptr) g_audio_client->Stop();
    if (g_audio_render != nullptr) {
        g_audio_render->Release();
        g_audio_render = nullptr;
    }
    if (g_audio_client != nullptr) {
        g_audio_client->Release();
        g_audio_client = nullptr;
    }
    g_audio_buffer_frames = 0;
    g_audio_channels = 0;
    g_audio_bytes_per_sample = 0;
    g_audio_float = false;
    g_audio_started = false;
    g_audio_submitted_frames = 0;
    g_audio_played_offset = 0;
    g_audio_device_name.clear();
}

std::wstring property_string(IMMDevice* device, const PROPERTYKEY& key) {
    IPropertyStore* store = nullptr;
    if (FAILED(device->OpenPropertyStore(STGM_READ, &store)) || store == nullptr) return L"";
    PROPVARIANT value;
    PropVariantInit(&value);
    std::wstring result;
    if (SUCCEEDED(store->GetValue(key, &value)) && value.vt == VT_LPWSTR && value.pwszVal != nullptr) {
        result = value.pwszVal;
    }
    PropVariantClear(&value);
    store->Release();
    return result;
}

std::wstring lower(std::wstring value) {
    std::transform(value.begin(), value.end(), value.begin(), [](wchar_t c) {
        return static_cast<wchar_t>(towlower(c));
    });
    return value;
}

bool device_matches(const std::wstring& wanted, const std::wstring& friendly, const std::wstring& description) {
    const std::wstring needle = lower(wanted);
    const std::wstring name = lower(friendly);
    const std::wstring detail = lower(description);
    const std::wstring combined = detail.empty() ? name : name + L" (" + detail + L")";
    return needle == name || needle == detail || needle == combined ||
        (!name.empty() && needle.find(name) != std::wstring::npos) ||
        (!detail.empty() && needle.find(detail) != std::wstring::npos);
}

IMMDevice* choose_device(IMMDeviceEnumerator* enumerator, const std::wstring& wanted) {
    if (wanted.empty()) {
        IMMDevice* device = nullptr;
        return SUCCEEDED(enumerator->GetDefaultAudioEndpoint(eRender, eMultimedia, &device))
            ? device : nullptr;
    }

    IMMDeviceCollection* devices = nullptr;
    if (FAILED(enumerator->EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE, &devices)) || devices == nullptr) {
        return nullptr;
    }
    UINT count = 0;
    devices->GetCount(&count);
    IMMDevice* match = nullptr;
    for (UINT index = 0; index < count && match == nullptr; ++index) {
        IMMDevice* candidate = nullptr;
        if (FAILED(devices->Item(index, &candidate)) || candidate == nullptr) continue;
        const std::wstring friendly = property_string(candidate, PKEY_Device_FriendlyName);
        const std::wstring description = property_string(candidate, PKEY_Device_DeviceDesc);
        if (device_matches(wanted, friendly, description)) {
            match = candidate;
            g_audio_device_name = friendly.empty() ? wanted : friendly;
        } else {
            candidate->Release();
        }
    }
    devices->Release();
    return match;
}

uint64_t played_frames_locked() {
    if (g_audio_client == nullptr) return g_audio_played_offset;
    UINT32 padding = 0;
    if (FAILED(g_audio_client->GetCurrentPadding(&padding))) padding = 0;
    const uint64_t queued = std::min<uint64_t>(padding, g_audio_submitted_frames);
    return g_audio_played_offset + g_audio_submitted_frames - queued;
}

void remember_played_and_reset_locked() {
    g_audio_played_offset = played_frames_locked();
    g_audio_submitted_frames = 0;
}

int open_audio(const std::wstring& wanted, int sample_rate, int channels, int bytes_per_sample, bool floating) {
    std::lock_guard<std::mutex> guard(g_audio_mutex);
    release_audio_locked();
    g_audio_error.clear();

    ComScope com;
    if (!com.ready()) {
        set_audio_error(L"COM initialization", com.result);
        return 0;
    }

    IMMDeviceEnumerator* enumerator = nullptr;
    HRESULT status = CoCreateInstance(
        __uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
        __uuidof(IMMDeviceEnumerator), reinterpret_cast<void**>(&enumerator));
    if (FAILED(status) || enumerator == nullptr) {
        set_audio_error(L"Audio endpoint discovery", status);
        return 0;
    }

    IMMDevice* device = choose_device(enumerator, wanted);
    enumerator->Release();
    if (device == nullptr) {
        g_audio_error = wanted.empty() ? L"Windows has no default audio endpoint" : L"The selected audio endpoint is unavailable";
        return 0;
    }
    if (g_audio_device_name.empty()) {
        g_audio_device_name = property_string(device, PKEY_Device_FriendlyName);
    }

    status = device->Activate(
        __uuidof(IAudioClient), CLSCTX_ALL, nullptr,
        reinterpret_cast<void**>(&g_audio_client));
    device->Release();
    if (FAILED(status) || g_audio_client == nullptr) {
        set_audio_error(L"Opening the audio endpoint", status);
        release_audio_locked();
        return 0;
    }

    WAVEFORMATEX format{};
    format.wFormatTag = floating ? WAVE_FORMAT_IEEE_FLOAT : WAVE_FORMAT_PCM;
    format.nChannels = static_cast<WORD>(channels);
    format.nSamplesPerSec = static_cast<DWORD>(sample_rate);
    format.wBitsPerSample = static_cast<WORD>(bytes_per_sample * 8);
    format.nBlockAlign = static_cast<WORD>(channels * bytes_per_sample);
    format.nAvgBytesPerSec = format.nSamplesPerSec * format.nBlockAlign;
    format.cbSize = 0;

#ifndef AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM
#define AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM 0x80000000
#endif
#ifndef AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY
#define AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY 0x08000000
#endif
    const DWORD flags = AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
    // 200 ms keeps network/decoder jitter away from the device without making controls sluggish.
    status = g_audio_client->Initialize(
        AUDCLNT_SHAREMODE_SHARED, flags, 2'000'000, 0, &format, nullptr);
    if (FAILED(status)) {
        set_audio_error(L"Starting WASAPI shared mode", status);
        release_audio_locked();
        return 0;
    }
    status = g_audio_client->GetBufferSize(&g_audio_buffer_frames);
    if (SUCCEEDED(status)) {
        status = g_audio_client->GetService(
            __uuidof(IAudioRenderClient), reinterpret_cast<void**>(&g_audio_render));
    }
    if (SUCCEEDED(status)) status = g_audio_client->Start();
    if (FAILED(status) || g_audio_render == nullptr) {
        set_audio_error(L"Starting the WASAPI render stream", status);
        release_audio_locked();
        return 0;
    }

    g_audio_channels = static_cast<UINT32>(channels);
    g_audio_bytes_per_sample = static_cast<UINT32>(bytes_per_sample);
    g_audio_float = floating;
    g_audio_started = true;
    return static_cast<int>(g_audio_buffer_frames * g_audio_channels * g_audio_bytes_per_sample);
}

int write_audio(const float* samples, int sample_count, float gain) {
    if (samples == nullptr || sample_count <= 0) return 0;
    std::lock_guard<std::mutex> guard(g_audio_mutex);
    ComScope com;
    if (!com.ready() || g_audio_client == nullptr || g_audio_render == nullptr || g_audio_channels == 0) return 0;

    const UINT32 total_frames = static_cast<UINT32>(sample_count) / g_audio_channels;
    UINT32 frame_at = 0;
    while (frame_at < total_frames) {
        UINT32 padding = 0;
        HRESULT status = g_audio_client->GetCurrentPadding(&padding);
        if (FAILED(status)) {
            set_audio_error(L"Reading WASAPI buffer state", status);
            break;
        }
        const UINT32 available = padding < g_audio_buffer_frames ? g_audio_buffer_frames - padding : 0;
        if (available == 0) {
            Sleep(2);
            continue;
        }
        const UINT32 frames = std::min(available, total_frames - frame_at);
        BYTE* target = nullptr;
        status = g_audio_render->GetBuffer(frames, &target);
        if (FAILED(status) || target == nullptr) {
            set_audio_error(L"Acquiring the WASAPI buffer", status);
            break;
        }
        const size_t first = static_cast<size_t>(frame_at) * g_audio_channels;
        const size_t values = static_cast<size_t>(frames) * g_audio_channels;
        if (g_audio_float) {
            auto* output = reinterpret_cast<float*>(target);
            for (size_t index = 0; index < values; ++index) {
                output[index] = std::clamp(samples[first + index] * gain, -1.0f, 1.0f);
            }
        } else if (g_audio_bytes_per_sample == 2) {
            auto* output = reinterpret_cast<int16_t*>(target);
            for (size_t index = 0; index < values; ++index) {
                const float value = std::clamp(samples[first + index] * gain, -1.0f, 1.0f);
                output[index] = static_cast<int16_t>(value * 32767.0f);
            }
        } else {
            auto* output = reinterpret_cast<int32_t*>(target);
            for (size_t index = 0; index < values; ++index) {
                const double value = std::clamp(static_cast<double>(samples[first + index] * gain), -1.0, 1.0);
                output[index] = static_cast<int32_t>(value * 2147483647.0);
            }
        }
        status = g_audio_render->ReleaseBuffer(frames, 0);
        if (FAILED(status)) {
            set_audio_error(L"Submitting the WASAPI buffer", status);
            break;
        }
        frame_at += frames;
        g_audio_submitted_frames += frames;
    }
    return static_cast<int>(frame_at * g_audio_channels * g_audio_bytes_per_sample);
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsFrame_nativeInstall(
    JNIEnv* env, jclass, jstring title) {
    return install_frame(find_window(env, title)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsFrame_nativeMinimize(JNIEnv*, jclass) {
    return send_system_command(SC_MINIMIZE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsFrame_nativeSetBackdrop(JNIEnv*, jclass, jint kind) {
    return set_backdrop(g_window, static_cast<int>(kind)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsFrame_nativeStartDrag(JNIEnv*, jclass) {
    HWND window = g_window;
    return window != nullptr && IsWindow(window) &&
        PostMessageW(window, WM_BITCHORD_DRAG, 0, 0) != 0
        ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsFrame_nativeToggleMaximize(JNIEnv*, jclass) {
    HWND window = g_window;
    if (window == nullptr || !IsWindow(window)) return JNI_FALSE;
    return send_system_command(IsZoomed(window) ? SC_RESTORE : SC_MAXIMIZE)
        ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsAudio_nativeOpen(
    JNIEnv* env,
    jclass,
    jstring device,
    jint sample_rate,
    jint channels,
    jint bytes_per_sample,
    jboolean floating) {
    const jchar* chars = env->GetStringChars(device, nullptr);
    const jsize length = env->GetStringLength(device);
    std::wstring wanted(reinterpret_cast<const wchar_t*>(chars), static_cast<size_t>(length));
    env->ReleaseStringChars(device, chars);
    return static_cast<jint>(open_audio(
        wanted,
        static_cast<int>(sample_rate),
        static_cast<int>(channels),
        static_cast<int>(bytes_per_sample),
        floating == JNI_TRUE));
}

JNIEXPORT jint JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsAudio_nativeWrite(
    JNIEnv* env, jclass, jfloatArray samples, jint count, jfloat gain) {
    if (samples == nullptr || count <= 0) return 0;
    const jsize available = env->GetArrayLength(samples);
    const jint safe_count = std::min(count, static_cast<jint>(available));
    jboolean copied = JNI_FALSE;
    auto* values = env->GetFloatArrayElements(samples, &copied);
    if (values == nullptr) return 0;
    const int written = write_audio(values, static_cast<int>(safe_count), static_cast<float>(gain));
    env->ReleaseFloatArrayElements(samples, values, JNI_ABORT);
    return static_cast<jint>(written);
}

JNIEXPORT jlong JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsAudio_nativeFramesPlayed(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> guard(g_audio_mutex);
    ComScope com;
    return static_cast<jlong>(played_frames_locked());
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsAudio_nativePause(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> guard(g_audio_mutex);
    ComScope com;
    if (g_audio_client != nullptr && g_audio_started && SUCCEEDED(g_audio_client->Stop())) {
        g_audio_started = false;
    }
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsAudio_nativeResume(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> guard(g_audio_mutex);
    ComScope com;
    if (g_audio_client != nullptr && !g_audio_started && SUCCEEDED(g_audio_client->Start())) {
        g_audio_started = true;
    }
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsAudio_nativeFlush(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> guard(g_audio_mutex);
    ComScope com;
    if (g_audio_client == nullptr) return;
    const bool restart = g_audio_started;
    g_audio_client->Stop();
    remember_played_and_reset_locked();
    g_audio_client->Reset();
    if (restart && SUCCEEDED(g_audio_client->Start())) g_audio_started = true;
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsAudio_nativeDrain(JNIEnv*, jclass) {
    while (true) {
        {
            std::lock_guard<std::mutex> guard(g_audio_mutex);
            ComScope com;
            if (g_audio_client == nullptr) return;
            UINT32 padding = 0;
            if (FAILED(g_audio_client->GetCurrentPadding(&padding)) || padding == 0) return;
        }
        Sleep(2);
    }
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsAudio_nativeClose(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> guard(g_audio_mutex);
    ComScope com;
    release_audio_locked();
}

JNIEXPORT jstring JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsAudio_nativeDeviceName(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> guard(g_audio_mutex);
    return env->NewString(
        reinterpret_cast<const jchar*>(g_audio_device_name.c_str()),
        static_cast<jsize>(g_audio_device_name.size()));
}

JNIEXPORT jstring JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsAudio_nativeLastError(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> guard(g_audio_mutex);
    return env->NewString(
        reinterpret_cast<const jchar*>(g_audio_error.c_str()),
        static_cast<jsize>(g_audio_error.size()));
}

}  // extern "C"
