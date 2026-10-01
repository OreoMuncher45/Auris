// The name and icon the shell shows on BitChord's media card.
//
// The shell names an SMTC session after the AppUserModelID of the window it was
// acquired from. Without one it falls back to the process — `java.exe` in a dev
// run, which no Start-menu shortcut describes — and the card reads "Unknown
// app". So the hidden SMTC window gets an explicit id, and that id's display
// name and icon are registered where the shell's resolver looks for unpackaged
// apps. Only that window: giving the whole process an id would regroup the main
// window on the taskbar away from its pinned shortcut.
//
// Plain Win32, shared by the C++/WinRT and the ABI builds of the library.

#pragma once

#include <windows.h>
#include <propkey.h>
#include <propvarutil.h>
#include <shellapi.h>
#include <shobjidl.h>

#include <mutex>
#include <string>

namespace smtc_identity {

inline std::mutex g_mutex;
inline std::wstring g_id;
inline std::wstring g_name;
inline std::wstring g_icon;

inline void remember(std::wstring id, std::wstring name, std::wstring icon) {
    std::lock_guard<std::mutex> guard(g_mutex);
    g_id = std::move(id);
    g_name = std::move(name);
    g_icon = std::move(icon);
}

inline void set_string(HKEY key, const wchar_t* name, const std::wstring& value) {
    RegSetValueExW(
        key, name, 0, REG_SZ, reinterpret_cast<const BYTE*>(value.c_str()),
        static_cast<DWORD>((value.size() + 1) * sizeof(wchar_t)));
}

/** Registers the id and tags [window] with it. Call before SMTC is acquired for the window. */
inline void apply(HWND window) {
    std::wstring id, name, icon;
    {
        std::lock_guard<std::mutex> guard(g_mutex);
        id = g_id;
        name = g_name;
        icon = g_icon;
    }
    if (id.empty() || window == nullptr) return;

    // HKCU, so no elevation; rewritten each start so a moved install keeps a valid icon path.
    const std::wstring path = L"Software\\Classes\\AppUserModelId\\" + id;
    HKEY key = nullptr;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, path.c_str(), 0, nullptr, 0, KEY_SET_VALUE, nullptr, &key, nullptr) ==
        ERROR_SUCCESS) {
        if (!name.empty()) set_string(key, L"DisplayName", name);
        if (!icon.empty()) set_string(key, L"IconUri", icon);
        RegCloseKey(key);
    }

    IPropertyStore* store = nullptr;
    if (FAILED(SHGetPropertyStoreForWindow(window, IID_IPropertyStore, reinterpret_cast<void**>(&store))) ||
        store == nullptr) {
        return;
    }
    PROPVARIANT value;
    if (SUCCEEDED(InitPropVariantFromString(id.c_str(), &value))) {
        store->SetValue(PKEY_AppUserModel_ID, value);
        PropVariantClear(&value);
    }
    if (!name.empty() && SUCCEEDED(InitPropVariantFromString(name.c_str(), &value))) {
        store->SetValue(PKEY_AppUserModel_RelaunchDisplayNameResource, value);
        PropVariantClear(&value);
    }
    store->Commit();
    store->Release();
}

}  // namespace smtc_identity
