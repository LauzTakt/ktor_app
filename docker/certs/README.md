Optional extra CA certificates (`*.crt`, PEM) trusted **only while building** the image.

Needed behind a TLS-intercepting proxy (e.g. corporate Netskope), otherwise Gradle can't download dependencies.
Leave empty elsewhere. The `*.crt` files here are git-ignored and never reach the runtime image.
