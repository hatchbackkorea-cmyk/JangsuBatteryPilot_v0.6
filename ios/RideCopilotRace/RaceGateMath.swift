import Foundation
import CoreLocation

// Pure geometry shared semantically with Android RaceGateMath.
enum RaceGateMathIOS {
    static func crossingFraction(previous: CLLocation, current: CLLocation, gate: RaceGate) -> Double? {
        let dt = current.timestamp.timeIntervalSince(previous.timestamp)
        guard dt > 0, dt <= 10 else { return nil }
        guard current.distance(from: previous) <= max(140.0, gate.widthM * 4.0) else { return nil }

        let latScale = 110_540.0
        let lonScale = 111_320.0 * cos(gate.lat * .pi / 180.0)
        func xy(_ c: CLLocationCoordinate2D) -> (Double, Double) {
            ((c.longitude - gate.lon) * lonScale, (c.latitude - gate.lat) * latScale)
        }
        let p1 = xy(previous.coordinate), p2 = xy(current.coordinate)
        let rad = gate.bearingDeg * .pi / 180.0
        let route = (sin(rad), cos(rad))
        let move = (p2.0 - p1.0, p2.1 - p1.1)
        guard move.0 * route.0 + move.1 * route.1 > 0 else { return nil }

        let half = gate.widthM / 2.0
        let perp = (cos(rad), -sin(rad))
        let a = (-perp.0 * half, -perp.1 * half)
        let b = (perp.0 * half, perp.1 * half)
        let s = (b.0 - a.0, b.1 - a.1)
        func cross(_ x: (Double, Double), _ y: (Double, Double)) -> Double { x.0 * y.1 - x.1 * y.0 }
        let den = cross(move, s)
        guard abs(den) > 1e-7 else { return nil }
        let qp = (a.0 - p1.0, a.1 - p1.1)
        let t = cross(qp, s) / den
        let u = cross(qp, move) / den
        return (0...1).contains(t) && (0...1).contains(u) ? t : nil
    }

    static func interpolatedCrossingDate(previous: CLLocation, current: CLLocation, gate: RaceGate) -> Date? {
        guard let f = crossingFraction(previous: previous, current: current, gate: gate) else { return nil }
        let dt = current.timestamp.timeIntervalSince(previous.timestamp)
        return previous.timestamp.addingTimeInterval(dt * f)
    }

    static func referenceElapsed(_ points: [RaceReferencePoint], routeM: Double) -> Int64? {
        guard points.count >= 2 else { return nil }
        if routeM <= points[0].routeM { return points[0].elapsedMs }
        if routeM >= points[points.count - 1].routeM { return points[points.count - 1].elapsedMs }
        var lo = 0, hi = points.count - 1
        while lo + 1 < hi {
            let mid = (lo + hi) / 2
            if points[mid].routeM <= routeM { lo = mid } else { hi = mid }
        }
        let a = points[lo], b = points[hi]
        let span = b.routeM - a.routeM
        guard span > 0.01 else { return a.elapsedMs }
        let f = min(1, max(0, (routeM - a.routeM) / span))
        return Int64((Double(a.elapsedMs) + Double(b.elapsedMs - a.elapsedMs) * f).rounded())
    }
}
