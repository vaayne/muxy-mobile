import Testing
@testable import Muxy

struct WebSocketOpenObserverTests {
    @Test func waitReturnsWhenSocketOpens() async throws {
        let observer = WebSocketOpenObserver()
        try await observer.waitUntilOpen(taskID: 1, timeout: .seconds(1)) {
            observer.markOpen(taskID: 1)
        }
    }

    @Test func waitThrowsWhenSocketFails() async {
        let observer = WebSocketOpenObserver()

        await #expect(throws: TransportError.closed) {
            try await observer.waitUntilOpen(taskID: 1, timeout: .seconds(1)) {
                observer.markFailed(taskID: 1, error: TransportError.closed)
            }
        }
    }

    @Test func waitThrowsWhenSocketTimesOut() async {
        let observer = WebSocketOpenObserver()

        await #expect(throws: TransportError.closed) {
            try await observer.waitUntilOpen(taskID: 1, timeout: .milliseconds(1), start: {})
        }
    }
}
