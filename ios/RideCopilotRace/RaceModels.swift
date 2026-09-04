import Foundation
import CoreLocation

// iOS RACE core scaffold. Keep field semantics identical to docs/RACE_PROTOCOL_V1.md and Android RaceProtocol.kt.
struct RaceGate: Codable {
    let name: String
    let type: String
    let routeM: Double
    let lat: Double
    let lon: Double
    let bearingDeg: Double
    let widthM: Double

    enum CodingKeys: String, CodingKey {
        case name, type, lat, lon
        case routeM = "route_m"
        case bearingDeg = "bearing_deg"
        case widthM = "width_m"
    }
}

struct RaceReferencePoint: Codable {
    let routeM: Double
    let elapsedMs: Int64
    enum CodingKeys: String, CodingKey { case routeM = "m"; case elapsedMs = "t" }
}

struct RaceSectorResult: Codable {
    let index: Int
    let name: String
    let sectorMs: Int64
    let splitMs: Int64
    let rank: Int?
    enum CodingKeys: String, CodingKey {
        case index, name, rank
        case sectorMs = "sector_ms"
        case splitMs = "split_ms"
    }
}

struct RaceEventConfig: Codable {
    let eventId: Int64
    let eventCode: String
    let name: String
    let courseId: Int64?
    let courseName: String
    let distanceM: Double
    let gates: [RaceGate]
    let reference: [RaceReferencePoint]
    let leaderName: String?
    let leaderElapsedMs: Int64?

    enum CodingKeys: String, CodingKey {
        case name, gates, reference
        case eventId = "event_id"
        case eventCode = "event_code"
        case courseId = "course_id"
        case courseName = "course_name"
        case distanceM = "distance_m"
        case leaderName = "leader_name"
        case leaderElapsedMs = "leader_elapsed_ms"
    }
}

protocol RaceLocationSource: AnyObject {
    var onLocation: ((CLLocation) -> Void)? { get set }
    func start() throws
    func stop()
}

final class PhoneRaceLocationSource: NSObject, RaceLocationSource, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    var onLocation: ((CLLocation) -> Void)?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.activityType = .fitness
        manager.pausesLocationUpdatesAutomatically = false
        manager.allowsBackgroundLocationUpdates = true
    }

    func start() throws {
        manager.requestAlwaysAuthorization()
        manager.startUpdatingLocation()
    }

    func stop() { manager.stopUpdatingLocation() }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        locations.forEach { onLocation?($0) }
    }
}
