# Direct cloud backend plan

DataBackup 3.x should support first-party cloud APIs directly. A direct backend means the Android app authenticates the user and transfers backup bytes to the provider's official HTTPS API without a WebDAV gateway, relay server, reverse proxy, or DataBackup-operated storage service.

## Preferred providers

### Google Drive

- Official Java client foundation: `googleapis/google-api-java-client`.
- Generated Drive API bindings: `googleapis/google-api-java-client-services`.
- Android authorization: Google Identity Services `AuthorizationClient`.
- Preferred delegated scope: `https://www.googleapis.com/auth/drive.file`, which limits access to files the user uses with or that are created by DataBackup.
- Transport target: Drive API v3.
- Large backups: use resumable upload sessions (`uploadType=resumable`).
- Non-final resumable chunks are aligned to 256 KiB.
- Recommended DataBackup path: `DataBackup/<device-or-profile>/<backup-id>/...`.

The 3.x implementation uses a thin Ktor transport over Drive REST for Archive-style transfers. This keeps the APK and dependency surface smaller while still transferring bytes directly to Google.

### Microsoft OneDrive

- Official maintained Java SDK: `microsoftgraph/msgraph-sdk-java`.
- Android authentication: Microsoft Authentication Library (MSAL) for Android.
- Do not use the archived `OneDrive/onedrive-sdk-android`.
- Transport target: Microsoft Graph v1.0.
- Preferred storage location: OneDrive application folder (`special/approot`).
- Preferred delegated scope: `Files.ReadWrite.AppFolder`.
- Large backups: create an upload session and upload sequential byte ranges directly to the returned OneDrive upload URL.
- Non-final chunks must be multiples of 320 KiB. DataBackup defaults to 5 MiB chunks.

Using the application folder avoids requesting general access to the user's OneDrive files. The upload-session URL is treated as a bearer credential and is never logged.

## Rustic: direct repository storage

There is an even better route for the Rustic backend than creating a local repository and uploading it afterwards.

DataBackup already vendors `rustic-rs/rustic_core`. Its `rustic_backend` supports OpenDAL, and the current upstream OpenDAL feature set explicitly includes both `services-gdrive` and `services-onedrive`. Rustic's OpenDAL backend accepts repository locations such as `opendal:gdrive:`; OpenDAL's Google Drive and OneDrive configurations accept an access token and a root path directly.

Therefore the preferred long-term topology is:

```
Android OAuth
   |
   +-- Google AuthorizationClient / Microsoft MSAL
   |
short-lived access token
   |
Rustic JNI
   |
rustic_backend / OpenDAL
   |
Google Drive or OneDrive official API
```

This is a real direct repository: Rustic packs, indexes, snapshots and config objects are written to the provider rather than staged as a complete local repository and mirrored later.

Do not enable `rustic_backend/opendal` blindly in the Android APK yet. Upstream currently enables many OpenDAL services together (S3, Dropbox, GCS, WebDAV and others), which would unnecessarily increase the native dependency and binary surface. DataBackup should either:
1. use a slim OpenDAL adapter compiled only with `services-gdrive` and `services-onedrive`; or
2. upstream a feature split to `rustic_backend` and consume the narrower features afterwards.

Until that native path is size-verified, the Ktor direct upload clients remain the Archive/generic-file transport and the authentication/bootstrap layer.

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

Archive should use the direct REST transport. Rustic should ultimately consume the provider through its repository backend rather than know about Drive/Graph HTTP details.

## Security rules

- Never embed OAuth client secrets, refresh tokens, access tokens, Rustic passwords, cookies, or upload-session URLs in backup metadata.
- Store renewable credentials only in app-private encrypted storage backed by Android Keystore.
- Treat resumable upload URLs as bearer credentials and redact them from `toString()` and logs.
- Request the narrowest practical provider scopes.
- Do not introduce a DataBackup-operated relay service.
- A provider outage or expired session must leave the backup/snapshot in a recoverable state.

## Implementation order

1. Common streaming/resumable transport.
2. Google Drive direct REST backend.
3. OneDrive direct App Folder backend.
4. Persistent transfer journal plus list/range-download primitives. **Implemented on `maint/3.x`.**
5. Google AuthorizationClient and Microsoft MSAL account flows.
6. Slim Rustic/OpenDAL `gdrive` + `onedrive` backend.
7. UI destination picker and end-to-end upload/download/restore tests.

WebDAV/SMB/SFTP remain optional generic backends, but are not the preferred first-party cloud path.
