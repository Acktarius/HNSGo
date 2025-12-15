# HNS Go

## What We Want to Achieve

HNS Go is an Android application that enables local resolution of Handshake domain names. The app syncs with the Handshake blockchain using SPV (Simplified Payment Verification) and runs local DNS over HTTPS (DoH) and DNS over TLS (DoT) servers, allowing you to resolve Handshake domains (e.g., website.conceal where .conceal is the TLD) directly on your Android device.

# Usage
## Geek Mode

1. Install and sync headers
2. Install certificate
3. Allow user certificate in Firefox
4. Add custom DoH in Firefox (`https://127.0.0.1:8443/dns-query`)
5. Trigger Firefox certificate acceptance (`https://127.0.0.1:8443/health`)  
6. *optional* **Ad Blocker**: Enable DNS-level ad blocking using well-maintained blacklists (StevenBlack hosts, OISD, AdGuard). Blocks ads and trackers by returning NXDOMAIN for blocked domains. Includes privacy mode for stricter blocking of telemetry and tracking domains.
7. **DANE Inspector**: Since Firefox doesn't (yet) check TLSA, we implemented a DANE Inspector where URLs can be input to verify TLSA

8. Browse on Firefox!

## Easy Mode

Browse using the HNS Go built-in WebView browser, which automatically enforces:

* **DoH Usage**: All DNS queries are routed through the local DoH resolver (`https://127.0.0.1:8443/dns-query`), ensuring no DNS leaks
* **Privacy Features**:
  - Third-party cookies blocked
  - Cross-site cookie clearing for maximum anonymity
  - Geolocation API disabled
  - File system access restricted
  - Automatic data clearing on app exit
  - No URL or browsing data logging

**Features**:
* **Favorites**: Star any page to save it to your favorites list
* **Search Engine Selection**: Choose from Qwant (default), DuckDuckGo, Google, Startpage, or Brave Search
* **Browsing History**: Recent history automatically saved (last 10 items shown)
* **RSS Feed Detection**: Automatically detects and displays RSS/Atom feeds in a readable format
* **Clean Tool**: One-tap clearing of all cookies and browsing history

Simply open the app, sync headers, and start browsing! All Handshake domains are resolved locally through your device.



## Acknowledgments

This project is inspired by [hnsd](https://github.com/handshake-org/hnsd), the Handshake SPV resolver. We acknowledge the hnsd project contributors and their MIT License.

Special thanks to Nathan and his [DANE checker](https://tools.cn02.woodburn.au/?domain=nathan.woodburn) tool, which provided valuable insights for implementing the DANE Inspector feature.

## License

MIT License - see [LICENSE](LICENSE) file for details.
