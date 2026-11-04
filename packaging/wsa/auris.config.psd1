# Auris for Windows (Android) - settings.
#
# Everything here is optional. Delete a line and the launcher falls back to
# its own behaviour: the APK in this folder, or the newest release on GitHub.

@{
    # Where to get Auris when there is no Auris.apk in this folder.
    # Leave empty to ask GitHub for the newest release.
    ApkUrl = ''

    # The Windows Subsystem for Android package and its system image.
    # Leave empty to ask GitHub for Microsoft's newest release.
    WsaPackageUrl = ''
    WsaSystemImageUrl = ''

    # The name WSA gives the window. The border stripper looks for it.
    WindowTitle = 'Auris'

    # Set to $false to leave the window's frame as Windows drew it.
    StripWindowFrame = $true
}
