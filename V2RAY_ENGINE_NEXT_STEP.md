# Xray integration notes

The project now builds the official XTLS/libXray Android AAR in CI and calls its `Invoke` API. The Xray VpnService is declared in a dedicated `:xray` process. This separation is intentional: libXray and WireGuard GoBackend each embed a Go runtime, and upstream libXray warns against loading two independent Go runtimes in one process.

The service creates an Android VpnService TUN, sets `env.xray.tun.fd`, registers socket protection, configures DNS protection, and calls `runXray` / `stopXray`. Verify generated gomobile Java bindings and first CI build against the currently pinned upstream libXray main branch. The upstream API is not guaranteed stable.
