import Foundation
import OSLog

actor WebSocketTransport: Transport {
    private let url: URL
    private let session: URLSession
    private let openObserver: WebSocketOpenObserver?
    private let openTimeout: Duration
    private var task: URLSessionWebSocketTask?

    init(url: URL, session: URLSession? = nil, openTimeout: Duration = .seconds(8)) {
        self.url = url
        self.openTimeout = openTimeout
        if let session {
            self.session = session
            self.openObserver = nil
        } else {
            let observer = WebSocketOpenObserver()
            self.openObserver = observer
            self.session = URLSession(configuration: .default, delegate: observer, delegateQueue: nil)
        }
    }

    func connect() async throws {
        let task = session.webSocketTask(with: url)
        self.task = task
        Log.transport.debug("WebSocket connecting to \(self.url.absoluteString, privacy: .public)")
        guard let openObserver else {
            task.resume()
            return
        }
        do {
            try await openObserver.waitUntilOpen(
                taskID: task.taskIdentifier,
                timeout: openTimeout,
                start: { task.resume() }
            )
            Log.transport.debug("WebSocket connected to \(self.url.absoluteString, privacy: .public)")
        } catch {
            task.cancel(with: .goingAway, reason: nil)
            self.task = nil
            Log.transport.error("WebSocket connection failed: \(error.localizedDescription, privacy: .public)")
            throw error
        }
    }

    func send(_ text: String) async throws {
        guard let task else { throw TransportError.notConnected }
        Log.transport.debug("WebSocket send: \(text, privacy: .private)")
        try await task.send(.string(text))
    }

    func receive() async throws -> String {
        guard let task else { throw TransportError.notConnected }

        while true {
            let message: URLSessionWebSocketTask.Message
            do {
                message = try await task.receive()
            } catch {
                Log.transport.error("WebSocket receive failed: \(error.localizedDescription, privacy: .public)")
                throw TransportError.closed
            }

            switch message {
            case let .string(text):
                Log.transport.debug("WebSocket recv: \(text, privacy: .private)")
                return text
            case let .data(data):
                guard let text = String(data: data, encoding: .utf8) else { continue }
                return text
            @unknown default:
                continue
            }
        }
    }

    func close() async {
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        Log.transport.debug("WebSocket closed")
    }
}

final class WebSocketOpenObserver: NSObject, URLSessionWebSocketDelegate, URLSessionTaskDelegate, @unchecked Sendable {
    private struct Waiter {
        let continuation: CheckedContinuation<Void, Error>
        let timeoutTask: Task<Void, Never>
    }

    private let lock = NSLock()
    private var waiters: [Int: Waiter] = [:]

    func waitUntilOpen(taskID: Int, timeout: Duration, start: () -> Void) async throws {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                let timeoutTask = Task {
                    try? await Task.sleep(for: timeout)
                    self.finish(taskID: taskID, result: .failure(TransportError.closed))
                }
                lock.lock()
                waiters[taskID] = Waiter(continuation: continuation, timeoutTask: timeoutTask)
                lock.unlock()
                start()
            }
        } onCancel: {
            finish(taskID: taskID, result: .failure(CancellationError()))
        }
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didOpenWithProtocol protocol: String?
    ) {
        markOpen(taskID: webSocketTask.taskIdentifier)
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
        reason: Data?
    ) {
        markFailed(taskID: webSocketTask.taskIdentifier, error: TransportError.closed)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error else { return }
        markFailed(taskID: task.taskIdentifier, error: error)
    }

    func markOpen(taskID: Int) {
        finish(taskID: taskID, result: .success(()))
    }

    func markFailed(taskID: Int, error: Error) {
        finish(taskID: taskID, result: .failure(error))
    }

    private func finish(taskID: Int, result: Result<Void, Error>) {
        lock.lock()
        let waiter = waiters.removeValue(forKey: taskID)
        lock.unlock()
        guard let waiter else { return }
        waiter.timeoutTask.cancel()
        waiter.continuation.resume(with: result)
    }
}
