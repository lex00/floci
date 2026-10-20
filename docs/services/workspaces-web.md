# Amazon WorkSpaces Secure Browser

**Protocol:** REST JSON

**Endpoint:** `/portals`, `/browserSettings`, `/dataProtectionSettings`, `/ipAccessSettings`,
`/networkSettings`, `/sessionLoggers`, `/trustStores`, `/userAccessLoggingSettings`,
`/userSettings` and `/tags/*`, SigV4 service `workspaces-web`

Floci emulates the WorkSpaces Secure Browser (WorkSpaces Web) control plane, where a portal
associates with up to eight settings resources. No browser sessions run.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateBrowserSettings` | Create browser settings |
| `CreateDataProtectionSettings` | Create data protection settings |
| `CreateIpAccessSettings` | Create IP access settings |
| `CreateNetworkSettings` | Create network settings |
| `CreateSessionLogger` | Create session logger |
| `CreateTrustStore` | Create trust store |
| `CreateUserAccessLoggingSettings` | Create user access logging settings |
| `CreateUserSettings` | Create user settings |
| `ListBrowserSettings` | List browser settings |
| `ListDataProtectionSettings` | List data protection settings |
| `ListIpAccessSettings` | List IP access settings |
| `ListNetworkSettings` | List network settings |
| `ListSessionLoggers` | List session loggers |
| `ListTrustStores` | List trust stores |
| `ListUserAccessLoggingSettings` | List user access logging settings |
| `ListUserSettings` | List user settings |
| `GetBrowserSettings` | Get browser settings |
| `GetDataProtectionSettings` | Get data protection settings |
| `GetIpAccessSettings` | Get IP access settings |
| `GetNetworkSettings` | Get network settings |
| `GetSessionLogger` | Get session logger |
| `GetTrustStore` | Get trust store |
| `GetUserAccessLoggingSettings` | Get user access logging settings |
| `GetUserSettings` | Get user settings |
| `UpdateBrowserSettings` | Update browser settings |
| `UpdateDataProtectionSettings` | Update data protection settings |
| `UpdateIpAccessSettings` | Update IP access settings |
| `UpdateNetworkSettings` | Update network settings |
| `UpdateSessionLogger` | Update session logger |
| `UpdateUserAccessLoggingSettings` | Update user access logging settings |
| `UpdateUserSettings` | Update user settings |
| `UpdateTrustStore` | Add or remove trust store certificates |
| `DeleteBrowserSettings` | Delete browser settings no portal uses |
| `DeleteDataProtectionSettings` | Delete data protection settings no portal uses |
| `DeleteIpAccessSettings` | Delete IP access settings no portal uses |
| `DeleteNetworkSettings` | Delete network settings no portal uses |
| `DeleteSessionLogger` | Delete session logger no portal uses |
| `DeleteTrustStore` | Delete trust store no portal uses |
| `DeleteUserAccessLoggingSettings` | Delete user access logging settings no portal uses |
| `DeleteUserSettings` | Delete user settings no portal uses |
| `ListTrustStoreCertificates` | List a trust store's certificates |
| `GetTrustStoreCertificate` | Get one certificate by thumbprint |
| `CreatePortal` | Create a portal |
| `ListPortals` | List portals |
| `GetPortal` | Get a portal |
| `UpdatePortal` | Update a portal |
| `DeletePortal` | Delete a portal and release its associations |
| `GetPortalServiceProviderMetadata` | Return the portal's SAML service provider metadata |
| `CreateIdentityProvider` | Create an identity provider on a portal |
| `GetIdentityProvider` | Get an identity provider |
| `UpdateIdentityProvider` | Update an identity provider |
| `DeleteIdentityProvider` | Delete an identity provider |
| `ListIdentityProviders` | List a portal's identity providers |
| `ListSessions` | List a portal's sessions, always empty |
| `GetSession` | Answer ResourceNotFoundException, since no sessions run |
| `ExpireSession` | Answer ResourceNotFoundException, since no sessions run |
| `AssociateBrowserSettings` | Associate browser settings with a portal |
| `AssociateDataProtectionSettings` | Associate data protection settings with a portal |
| `AssociateIpAccessSettings` | Associate IP access settings with a portal |
| `AssociateNetworkSettings` | Associate network settings with a portal |
| `AssociateSessionLogger` | Associate session logger with a portal |
| `AssociateTrustStore` | Associate trust store with a portal |
| `AssociateUserAccessLoggingSettings` | Associate user access logging settings with a portal |
| `AssociateUserSettings` | Associate user settings with a portal |
| `DisassociateBrowserSettings` | Remove browser settings from a portal |
| `DisassociateDataProtectionSettings` | Remove data protection settings from a portal |
| `DisassociateIpAccessSettings` | Remove IP access settings from a portal |
| `DisassociateNetworkSettings` | Remove network settings from a portal |
| `DisassociateSessionLogger` | Remove session logger from a portal |
| `DisassociateTrustStore` | Remove trust store from a portal |
| `DisassociateUserAccessLoggingSettings` | Remove user access logging settings from a portal |
| `DisassociateUserSettings` | Remove user settings from a portal |
<!-- floci:actions:end -->

Tagging rides the shared `/tags/{resourceArn}` routes, so `TagResource`, `UntagResource` and
`ListTagsForResource` work against any portal or settings ARN.

## Behavior

- A portal reports `Incomplete` until browser, network and user settings are associated with it,
  and `Active` after.
- Associating a settings resource with a portal records the portal in its
  `associatedPortalArns`. Deleting settings that a portal still uses answers `ConflictException`,
  and deleting a portal releases its associations.
- A trust store parses each X.509 certificate it is given and lists it by SHA-256 thumbprint,
  subject, issuer and validity dates.
- User settings store `brandingConfigurationInput` as `brandingConfiguration`, with image metadata
  derived from each image's S3 URI.
- An identity provider's ARN carries its portal's id, and deleting a portal deletes its identity
  providers.
- No browser sessions run, so `ListSessions` returns an empty list for an existing portal.

## Not emulated

- Browser sessions
