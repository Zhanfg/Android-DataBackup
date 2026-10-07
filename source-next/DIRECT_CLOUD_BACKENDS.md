# Direct cloud backend plan

DataBackup 3.x should support first-party cloud APIs directly. A direct backend means the Android app authenticates the user and transfers backup bytes to the provider's official HTTPS API without a WebDAV gateway, relay server, reverse proxy, or DataBackup-operated storage service.

## Preferred providers

### Google Drive

- Official Java client foundation: `googleapis/google-api-java-client`.
- Generated Drive API bindings: `googleapis/google-api-java-client-services`.
- Transport target: Drive API v3.
- Large backups: use resumable upload sessions (`uploadType=resumable`).
- Authentication: OAuth 2.0 user authorization; tokens stay in app-private credential storage.
- Recommended DataBackup path: `DataBackup/<device-or-profile>/<backup-id>/...`.

For DataBackup, a thin Ktor implementation over the Drive REST API is preferred over importing the full generated client unless the generated client materially reduces auth or upload complexity. This keeps APK size and dependency surface lower while still connecting directly to Google.

### Microsoft OneDrive

- Official maintained Java SDK: `microsoftgraph/msgraph-sdk-java`.
- Do not use the archived `OneDrive/onedrive-sdk-android`; Microsoft directs Android projects to the Java/Graph SDK.
- Transport target: Microsoft Graph v1.0.
- Large backups: create an upload session and upload sequential byte ranges directly to the returned OneDrive upload URL.
- Chunk sizes must be multiples of 320 KiB when chunking. Prefer 5-10 MiB chunks for ordinary mobile networks and persist upload-session state for resume.
- Authentication: delegated OAuth through Microsoft's identity platform; tokens stay in app-private credential storage.

As with Drive, DataBackup may use a small Ktor Graph client rather than the full SDK if that produces a smaller and more controllable binary.

## Backend contract

Direct cloud support should sit below backup format logic:

```
CloudBackend
  authenticate / refresh
  stat
  list
  mkdir
  uploadResumable
  downloadRange
  delete
  move
```

The Archive and Rustic backup engines should not know whether storage is local, Drive, or OneDrive. They should consume a storage abstraction capable of streaming and resumable I/O.

## Security rules

- Never embed OAuth client secrets, refresh tokens, access tokens, Rustic passwords, cookies, or upload-session URLs in backup metadata.
- Store renewable credentials only in app-private encrypted storage backed by Android Keystore.
- Treat resumable upload URLs as bearer credentials and redact them from logs.
- Request the narrowest practical provider scopes.
- Do not introduce a DataBackup-operated relay service.
- A provider outage or expired session must leave the local backup/snapshot in a recoverable state.

## Implementation order

1. Common streaming/resumable backend contract.
2. Google Drive direct backend.
3. OneDrive direct backend.
4. Persistent transfer journal and resume.
5. UI account connection and destination picker.
6. End-to-end upload/download/restore tests.

WebDAV/SMB/SFTP remain optional generic backends, but should no longer be the only cloud path.
