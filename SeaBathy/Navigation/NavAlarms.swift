import AVFoundation
import AudioToolbox
import CoreLocation
import UIKit
import UserNotifications

/// The navigation alarms, each with its own number of beeps and repeat interval (Android NavAlarm).
enum NavAlarm: CaseIterable {
    case arrival, finished, offCourse, shallow

    var beeps: Int {
        switch self { case .arrival: return 2; case .finished: return 3; case .offCourse: return 3; case .shallow: return 4 }
    }

    /// Seconds between repeats while the alarm stays active; nil sounds once.
    var repeatSeconds: TimeInterval? {
        switch self { case .arrival, .finished: return nil; case .offCourse: return 20; case .shallow: return 8 }
    }
}

/**
 Sounds, vibrates and (in the background) posts a notification for navigation
 alarms. Repeating alarms sound again while they stay active; muting silences
 sound and vibration but the banner still shows.
 */
@MainActor
final class NavAlarmPlayer {
    var soundOn = true
    var vibrateOn = true
    var muted = false

    private var lastSounded: [NavAlarm: Date] = [:]
    private var player: AVAudioPlayer?
    private lazy var beepData: [Int: Data] = [:]

    /// Sounds the alarm now (one-off alarms) or when due (repeating ones). `message` is for the notification.
    func trigger(_ alarm: NavAlarm, message: String) {
        let now = Date()
        if let last = lastSounded[alarm] {
            guard let repeatAfter = alarm.repeatSeconds, now.timeIntervalSince(last) >= repeatAfter else { return }
        }
        lastSounded[alarm] = now
        play(alarm.beeps)
        notifyIfInBackground(message)
    }

    /// The alarm's condition has gone: it may sound again next time.
    func clear(_ alarm: NavAlarm) { lastSounded[alarm] = nil }

    func clearAll() { lastSounded.removeAll() }

    /// Asks once for permission to show alarms while the app is in the background.
    static func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    private func play(_ beeps: Int) {
        if muted { return }
        if soundOn {
            do {
                let session = AVAudioSession.sharedInstance()
                try session.setCategory(.playback, mode: .default, options: [.duckOthers])
                try session.setActive(true)
                let data = beepData[beeps] ?? Self.makeBeeps(beeps)
                beepData[beeps] = data
                let p = try AVAudioPlayer(data: data)
                p.volume = 1
                p.play()
                player = p
            } catch {
                DiagnosticLog.log("NavAlarms", "couldn't play alarm: \(error)")
            }
        }
        if vibrateOn {
            if UIApplication.shared.applicationState == .active {
                UINotificationFeedbackGenerator().notificationOccurred(.warning)
            }
            for i in 0..<beeps {
                DispatchQueue.main.asyncAfter(deadline: .now() + Double(i) * 0.4) {
                    AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
                }
            }
        }
    }

    private func notifyIfInBackground(_ message: String) {
        guard UIApplication.shared.applicationState != .active else { return }
        let content = UNMutableNotificationContent()
        content.title = "SeaBathy navigation"
        content.body = message
        if soundOn && !muted { content.sound = .default }
        let request = UNNotificationRequest(identifier: "nav-alarm-\(UUID().uuidString)", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request, withCompletionHandler: nil)
    }

    /// A short WAV of `count` 880 Hz beeps (220 ms each, 120 ms apart), like Android's tone generator.
    static func makeBeeps(_ count: Int) -> Data {
        let rate = 22_050
        let beepSamples = rate * 220 / 1000
        let gapSamples = rate * 120 / 1000
        var samples: [Int16] = []
        samples.reserveCapacity(count * (beepSamples + gapSamples))
        for _ in 0..<max(count, 1) {
            for i in 0..<beepSamples {
                let t = Double(i) / Double(rate)
                // Short fade in and out so the beep doesn't click.
                let edge = min(Double(i), Double(beepSamples - i)) / Double(rate / 200)
                let envelope = min(1.0, edge)
                samples.append(Int16(sin(2 * .pi * 880 * t) * 0.8 * envelope * Double(Int16.max)))
            }
            samples.append(contentsOf: [Int16](repeating: 0, count: gapSamples))
        }
        var data = Data()
        func le32(_ v: UInt32) { var x = v.littleEndian; withUnsafeBytes(of: &x) { data.append(contentsOf: $0) } }
        func le16(_ v: UInt16) { var x = v.littleEndian; withUnsafeBytes(of: &x) { data.append(contentsOf: $0) } }
        let byteCount = UInt32(samples.count * 2)
        data.append(contentsOf: Array("RIFF".utf8)); le32(36 + byteCount)
        data.append(contentsOf: Array("WAVE".utf8))
        data.append(contentsOf: Array("fmt ".utf8)); le32(16); le16(1); le16(1)
        le32(UInt32(rate)); le32(UInt32(rate * 2)); le16(2); le16(16)
        data.append(contentsOf: Array("data".utf8)); le32(byteCount)
        for s in samples { le16(UInt16(bitPattern: s)) }
        return data
    }
}

/**
 Magnetic variation (east-positive degrees) for magnetic bearings. iOS has no
 public world magnetic model, so it is measured from the compass: true heading
 minus magnetic heading. The last value is remembered between launches; before
 any reading it is 0.
 */
@MainActor
final class NavVariation: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private(set) var current: Double
    private var running = false
    private static let key = "nav_last_variation"

    override init() {
        current = UserDefaults.standard.double(forKey: Self.key)
        super.init()
        manager.delegate = self
        manager.headingFilter = 1
    }

    func variation(_ lat: Double, _ lng: Double) -> Double { current }

    /// Heading updates run only while magnetic bearings are shown.
    func setNeeded(_ needed: Bool) {
        guard CLLocationManager.headingAvailable() else { return }
        if needed && !running {
            manager.startUpdatingHeading()
            running = true
        } else if !needed && running {
            manager.stopUpdatingHeading()
            running = false
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        guard newHeading.trueHeading >= 0, newHeading.headingAccuracy >= 0 else { return }
        let diff = NavFormat.turnDegrees(from: newHeading.magneticHeading, to: newHeading.trueHeading)
        Task { @MainActor in
            self.current = diff
            UserDefaults.standard.set(diff, forKey: Self.key)
        }
    }
}
