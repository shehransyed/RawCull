# Security policy

## Supported versions

Only the [latest release](https://github.com/shehransyed/RawCull/releases/latest) gets fixes.

## Reporting a vulnerability

Please **don't open a public issue** for a security problem. Report it privately through GitHub
instead: open the repository's **Security** tab and choose **Report a vulnerability**.

Include what you found, how to reproduce it, and which version and device you used. You can expect
a first reply within a week. Once a fix is released, you'll be credited in the release notes unless
you'd rather not be.

## Scope

RawCull has no internet permission and no server, so most of the risk lies in how it handles files:
for example, a crafted RAW, JPEG or XMP file that crashes the app or makes it write outside the
folders you granted, or an import or delete that touches files it shouldn't.
