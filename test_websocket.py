import asyncio
import websockets
import json
import subprocess
import sys
import os

DEFAULT_PORT = 8081
DEFAULT_TOKEN = os.environ.get("PORTAL_TOKEN", "").strip()


def setup_adb_forward(port: int = DEFAULT_PORT) -> bool:
    """Set up ADB port forwarding for WebSocket connection."""
    print(f"Setting up ADB forward tcp:{port} -> tcp:{port}...")
    try:
        result = subprocess.run(
            ["adb", "forward", f"tcp:{port}", f"tcp:{port}"],
            capture_output=True,
            text=True,
        )
        if result.returncode == 0:
            print(f"✅ ADB forward established on port {port}")
            return True
        else:
            print(f"❌ ADB forward failed: {result.stderr}")
            return False
    except FileNotFoundError:
        print("❌ ADB not found. Make sure Android SDK platform-tools is in PATH")
        return False
    except Exception as e:
        print(f"❌ Error setting up ADB forward: {e}")
        return False


def build_ws_uri(port: int, token: str) -> str:
    if token:
        return f"ws://localhost:{port}/?token={token}"
    return f"ws://localhost:{port}"


async def test_connection(port: int = DEFAULT_PORT, token: str = ""):
    uri = build_ws_uri(port, token)
    print(f"Connecting to {uri}...")

    try:
        async with websockets.connect(uri) as websocket:
            print("✅ Connected successfully!")

            # Test 1: Ping
            print("\nSending PING...")
            ping_event = {"type": "PING", "timestamp": 123456789}
            await websocket.send(json.dumps(ping_event))

            # Wait for response
            print("Waiting for response...")
            response = await websocket.recv()
            print(f"Received: {response}")

            data = json.loads(response)
            if data.get("type") == "PONG":
                print("✅ PING/PONG Test Passed")
            else:
                print("❌ PING/PONG Test Failed")

            print("\n" + "=" * 50)
            print("Listening for events (Ctrl+C to stop)...")
            print("Trigger a notification on your phone to see it here!")
            print("=" * 50 + "\n")

            while True:
                msg = await websocket.recv()
                try:
                    event = json.loads(msg)
                    event_type = event.get("type", "UNKNOWN")
                    timestamp = event.get("timestamp", "")
                    payload = event.get("payload", {})

                    print(f"[{event_type}] {timestamp}")
                    if isinstance(payload, dict):
                        for key, value in payload.items():
                            print(f"  {key}: {value}")
                    else:
                        print(f"  payload: {payload}")
                    print()
                except json.JSONDecodeError:
                    print(f"Raw event: {msg}\n")

    except ConnectionRefusedError:
        print("❌ Connection Failed: Is the app running and service enabled?")
        print("   Make sure the WebSocket server is enabled in the app settings.")
    except Exception as e:
        print(f"❌ Error: {e}")


def main():
    port = DEFAULT_PORT
    token = DEFAULT_TOKEN

    # Parse optional port argument
    if len(sys.argv) > 1:
        try:
            port = int(sys.argv[1])
        except ValueError:
            print(f"Usage: {sys.argv[0]} [port] [token]")
            print(f"  Default port: {DEFAULT_PORT}")
            sys.exit(1)

    if len(sys.argv) > 2:
        token = sys.argv[2].strip()

    # Set up ADB forwarding first
    if not setup_adb_forward(port):
        print("\nContinuing anyway in case forward is already set up...")

    # Run the WebSocket listener
    try:
        asyncio.run(test_connection(port, token))
    except KeyboardInterrupt:
        print("\nTest stopped by user")


if __name__ == "__main__":
    main()
