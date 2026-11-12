import AVFoundation
import Combine
import MediaPlayer
import SwiftUI
import UniformTypeIdentifiers

@main
struct AuriPortApp: App {
    @StateObject private var state = AppState()
    var body: some Scene {
        WindowGroup {
            AuriRootView()
                .environmentObject(state)
                .preferredColorScheme(.dark)
        }
    }
}

@MainActor
final class AppState: ObservableObject {
    @Published var selectedTab: AppTab = .home
    @Published var searchText = ""
    let library = MusicLibrary()
    let player = AudioPlayerService()
    private var subscriptions = Set<AnyCancellable>()

    init() {
        library.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &subscriptions)
        player.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &subscriptions)
    }
}

enum AppTab: String, CaseIterable, Identifiable {
    case home = "Home", library = "Library", search = "Search", settings = "Settings"
    var id: String { rawValue }
    var icon: String {
        switch self {
        case .home: "house.fill"
        case .library: "square.stack.fill"
        case .search: "magnifyingglass"
        case .settings: "slider.horizontal.3"
        }
    }
}

struct MusicTrack: Identifiable, Codable, Hashable {
    var id = UUID()
    var title: String
    var artist = "Unknown Artist"
    var album = "Unknown Album"
    var duration: Double = 0
    var url: URL

    static func imported(_ url: URL) -> MusicTrack {
        let name = url.deletingPathExtension().lastPathComponent
        let parts = name.components(separatedBy: " - ")
        if parts.count > 1 {
            return MusicTrack(title: parts.dropFirst().joined(separator: " - "), artist: parts[0], url: url)
        }
        return MusicTrack(title: name, url: url)
    }
}

@MainActor
final class MusicLibrary: ObservableObject {
    @Published private(set) var tracks: [MusicTrack] = []
    private let key = "auri.port.ios.library"

    init() {
        if let data = UserDefaults.standard.data(forKey: key),
           let stored = try? JSONDecoder().decode([MusicTrack].self, from: data) {
            tracks = stored.filter { FileManager.default.fileExists(atPath: $0.url.path) }
        }
    }

    func importFiles(_ urls: [URL]) {
        let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Auri Music", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        for source in urls {
            let access = source.startAccessingSecurityScopedResource()
            defer { if access { source.stopAccessingSecurityScopedResource() } }
            let destination = folder.appendingPathComponent(source.lastPathComponent)
            do {
                if !FileManager.default.fileExists(atPath: destination.path) {
                    try FileManager.default.copyItem(at: source, to: destination)
                }
                if !tracks.contains(where: { $0.url == destination }) {
                    tracks.append(.imported(destination))
                }
            } catch { print("Auri import error: \(error)") }
        }
        persist()
    }

    func delete(at offsets: IndexSet) {
        let removed = offsets.map { tracks[$0] }
        removed.forEach { try? FileManager.default.removeItem(at: $0.url) }
        tracks.remove(atOffsets: offsets)
        persist()
    }

    private func persist() {
        if let data = try? JSONEncoder().encode(tracks) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }
}

@MainActor
final class AudioPlayerService: ObservableObject {
    @Published private(set) var current: MusicTrack?
    @Published private(set) var playing = false
    @Published private(set) var elapsed: Double = 0
    @Published private(set) var length: Double = 0
    @Published var error: String?
    private var engine: AVPlayer?
    private var queue: [MusicTrack] = []
    private var index = 0
    private var timeToken: Any?
    private var endToken: NSObjectProtocol?

    init() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
            try AVAudioSession.sharedInstance().setActive(true)
        } catch { error = error.localizedDescription }
        installRemoteControls()
    }

    func play(_ track: MusicTrack, in tracks: [MusicTrack]) {
        queue = tracks
        index = tracks.firstIndex(of: track) ?? 0
        start()
    }

    func toggle() {
        guard let engine else { return }
        if playing { engine.pause() } else { engine.play() }
        playing.toggle()
        updateNowPlaying()
    }

    func next() {
        guard !queue.isEmpty else { return }
        index = (index + 1) % queue.count
        start()
    }

    func previous() {
        guard !queue.isEmpty else { return }
        if elapsed > 3 { seek(0) } else {
            index = (index - 1 + queue.count) % queue.count
            start()
        }
    }

    func seek(_ seconds: Double) {
        let value = max(0, seconds)
        engine?.seek(to: CMTime(seconds: value, preferredTimescale: 600))
        elapsed = value
    }

    func dismissError() { error = nil }

    private func start() {
        guard queue.indices.contains(index) else { return }
        cleanup()
        let track = queue[index]
        current = track
        elapsed = 0
        length = track.duration
        let item = AVPlayerItem(url: track.url)
        let player = AVPlayer(playerItem: item)
        engine = player
        timeToken = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main
        ) { [weak self, weak player] time in
            Task { @MainActor in
                guard let self else { return }
                if time.seconds.isFinite { self.elapsed = time.seconds }
                if let seconds = player?.currentItem?.duration.seconds, seconds.isFinite, seconds > 0 {
                    self.length = seconds
                }
                self.updateNowPlaying()
            }
        }
        endToken = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main
        ) { [weak self] _ in Task { @MainActor in self?.next() } }
        player.play()
        playing = true
        updateNowPlaying()
    }

    private func installRemoteControls() {
        let commands = MPRemoteCommandCenter.shared()
        commands.playCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.engine?.play(); self?.playing = true }
            return .success
        }
        commands.pauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.engine?.pause(); self?.playing = false }
            return .success
        }
        commands.nextTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.next() }
            return .success
        }
        commands.previousTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.previous() }
            return .success
        }
        commands.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            Task { @MainActor in self?.seek(event.positionTime) }
            return .success
        }
    }

    private func updateNowPlaying() {
        guard let current else { return }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [
            MPMediaItemPropertyTitle: current.title,
            MPMediaItemPropertyArtist: current.artist,
            MPMediaItemPropertyAlbumTitle: current.album,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: elapsed,
            MPNowPlayingInfoPropertyPlaybackRate: playing ? 1.0 : 0.0,
            MPMediaItemPropertyPlaybackDuration: length
        ]
    }

    private func cleanup() {
        if let timeToken, let engine { engine.removeTimeObserver(timeToken) }
        timeToken = nil
        if let endToken { NotificationCenter.default.removeObserver(endToken) }
        endToken = nil
    }

    deinit {
        if let timeToken, let engine { engine.removeTimeObserver(timeToken) }
        if let endToken { NotificationCenter.default.removeObserver(endToken) }
    }
}

struct AuriRootView: View {
    @EnvironmentObject private var app: AppState
    @State private var importing = false
    var body: some View {
        ZStack(alignment: .bottom) {
            Color.black.ignoresSafeArea()
            Group {
                switch app.selectedTab {
                case .home: HomeScreen(importMusic: { importing = true })
                case .library: LibraryScreen(importMusic: { importing = true })
                case .search: SearchScreen()
                case .settings: SettingsScreen()
                }
            }
            VStack(spacing: 10) {
                if app.player.current != nil { MiniPlayer() .padding(.horizontal, 16) }
                HStack {
                    ForEach(AppTab.allCases) { tab in
                        Button { app.selectedTab = tab } label: {
                            VStack(spacing: 5) {
                                Image(systemName: tab.icon).font(.system(size: 17, weight: .semibold))
                                Text(tab.rawValue).font(.system(size: 10, weight: .medium))
                            }
                            .foregroundStyle(app.selectedTab == tab ? .white : .gray)
                            .frame(maxWidth: .infinity).padding(.vertical, 12)
                        }.buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 12)
                .background(.ultraThinMaterial, in: Capsule())
                .overlay(Capsule().stroke(.white.opacity(0.1), lineWidth: 1))
                .padding(.horizontal, 24).padding(.bottom, 8)
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.audio], allowsMultipleSelection: true) { result in
            if case .success(let urls) = result { app.library.importFiles(urls) }
        }
        .alert("Playback error", isPresented: Binding(get: { app.player.error != nil }, set: { if !$0 { app.player.dismissError() } })) {
            Button("OK", role: .cancel) { app.player.dismissError() }
        } message: { Text(app.player.error ?? "") }
    }
}

struct HomeScreen: View {
    @EnvironmentObject private var app: AppState
    let importMusic: () -> Void
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                HStack {
                    VStack(alignment: .leading, spacing: 5) {
                        Text("A U R I S").font(.system(size: 12, weight: .black)).foregroundStyle(.white.opacity(0.55))
                        Text("Listen closer.").font(.system(size: 32, weight: .bold, design: .rounded))
                    }
                    Spacer()
                    Button(action: importMusic) {
                        Image(systemName: "plus").font(.system(size: 17, weight: .bold))
                            .foregroundStyle(.black).frame(width: 42, height: 42).background(.white, in: Circle())
                    }
                }
                if app.library.tracks.isEmpty {
                    ContentUnavailableView {
                        Label("Your music starts here", systemImage: "waveform")
                    } description: {
                        Text("Import audio files from your iPhone to build your library.")
                    } actions: {
                        Button("Import music", action: importMusic).buttonStyle(.borderedProminent).tint(.white).foregroundStyle(.black)
                    }.padding(.top, 65)
                } else {
                    Text("Recently added").font(.system(size: 22, weight: .bold, design: .rounded))
                    ForEach(app.library.tracks.reversed()) { track in
                        TrackCell(track: track) { app.player.play(track, in: app.library.tracks) }
                    }
                }
            }.padding(.horizontal, 22).padding(.top, 18).padding(.bottom, 160)
        }
    }
}

struct LibraryScreen: View {
    @EnvironmentObject private var app: AppState
    let importMusic: () -> Void
    var body: some View {
        NavigationStack {
            List {
                ForEach(app.library.tracks) { track in
                    TrackCell(track: track) { app.player.play(track, in: app.library.tracks) }
                        .listRowBackground(Color.white.opacity(0.04))
                }
                .onDelete(perform: app.library.delete)
            }
            .scrollContentBackground(.hidden).background(.black)
            .navigationTitle("Library")
            .toolbar { ToolbarItem(placement: .navigationBarTrailing) { Button(action: importMusic) { Image(systemName: "plus") } } }
        }
    }
}

struct SearchScreen: View {
    @EnvironmentObject private var app: AppState
    private var matches: [MusicTrack] {
        guard !app.searchText.isEmpty else { return [] }
        return app.library.tracks.filter {
            $0.title.localizedCaseInsensitiveContains(app.searchText) ||
            $0.artist.localizedCaseInsensitiveContains(app.searchText) ||
            $0.album.localizedCaseInsensitiveContains(app.searchText)
        }
    }
    var body: some View {
        NavigationStack {
            VStack {
                TextField("Songs, artists, albums", text: $app.searchText).textFieldStyle(.roundedBorder).padding()
                if app.searchText.isEmpty {
                    Text("Search your imported music").foregroundStyle(.white.opacity(0.55)).padding(.top, 70)
                } else {
                    List(matches) { track in
                        TrackCell(track: track) { app.player.play(track, in: matches) }
                            .listRowBackground(Color.white.opacity(0.04))
                    }.scrollContentBackground(.hidden)
                }
                Spacer(minLength: 0)
            }.background(.black).navigationTitle("Search")
        }
    }
}

struct SettingsScreen: View {
    var body: some View {
        NavigationStack {
            List {
                Section("Playback") {
                    LabeledContent("Audio engine", value: "AVFoundation")
                    LabeledContent("Output", value: "iOS audio session")
                }
                Section("About") {
                    LabeledContent("App", value: "Auri PORT iOS")
                    LabeledContent("Architecture", value: "Native SwiftUI")
                    LabeledContent("Minimum iOS", value: "16.0")
                }
                Section {
                    Text("Streaming providers, verified FLAC decoding, lyrics, downloads and advanced Auris features will be ported in dedicated native modules.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }.scrollContentBackground(.hidden).background(.black).navigationTitle("Settings")
        }
    }
}

struct TrackCell: View {
    let track: MusicTrack
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                RoundedRectangle(cornerRadius: 10).fill(.white.opacity(0.09)).frame(width: 48, height: 48)
                    .overlay(Image(systemName: "waveform").foregroundStyle(.white.opacity(0.7)))
                VStack(alignment: .leading, spacing: 4) {
                    Text(track.title).font(.system(size: 14, weight: .semibold)).foregroundStyle(.white).lineLimit(1)
                    Text(track.artist).font(.system(size: 12)).foregroundStyle(.white.opacity(0.55)).lineLimit(1)
                }
                Spacer()
                Image(systemName: "ellipsis").foregroundStyle(.white.opacity(0.45))
            }.padding(.vertical, 5).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
}

struct MiniPlayer: View {
    @EnvironmentObject private var app: AppState
    var body: some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 8).fill(.white.opacity(0.1)).frame(width: 42, height: 42)
                .overlay(Image(systemName: "waveform").foregroundStyle(.white.opacity(0.8)))
            VStack(alignment: .leading, spacing: 3) {
                Text(app.player.current?.title ?? "").font(.system(size: 13, weight: .semibold)).lineLimit(1)
                Text(app.player.current?.artist ?? "").font(.system(size: 11)).foregroundStyle(.white.opacity(0.55))
            }
            Spacer()
            Button { app.player.previous() } label: { Image(systemName: "backward.end.fill") }
            Button { app.player.toggle() } label: {
                Image(systemName: app.player.playing ? "pause.fill" : "play.fill").font(.system(size: 18, weight: .bold))
            }
            Button { app.player.next() } label: { Image(systemName: "forward.end.fill") }
        }.foregroundStyle(.white).padding(12)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(.white.opacity(0.1), lineWidth: 1))
    }
}

struct EmptyLibraryState: View {
    let importMusic: () -> Void
    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: "waveform").font(.system(size: 42)).foregroundStyle(.white.opacity(0.55))
            Text("Your music starts here").font(.system(size: 20, weight: .bold, design: .rounded))
            Text("Import audio files from your iPhone to build your Auris library.")
                .font(.system(size: 14)).foregroundStyle(.white.opacity(0.55)).multilineTextAlignment(.center)
            Button(action: importMusic) {
                Label("Import music", systemImage: "plus").font(.system(size: 14, weight: .semibold))
                    .padding(.horizontal, 18).padding(.vertical, 11)
                    .background(.white, in: Capsule()).foregroundStyle(.black)
            }
        }.frame(maxWidth: .infinity).padding(24)
    }
}
