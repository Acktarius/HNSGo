# HNS Go

## What We Want to Achieve

HNS Go is an Android application that enables local resolution of Handshake domain names. The app syncs with the Handshake blockchain using SPV (Simplified Payment Verification) and runs local DNS over HTTPS (DoH) and DNS over TLS (DoT) servers, allowing you to resolve Handshake domains (e.g., website.conceal where .conceal is the TLD) directly on your Android device.

## Usage

1. Install and sync headers
2. Install certificate
3. Allow user certificate in Firefox
4. Add custom DoH in Firefox (`https://127.0.0.1:8443/dns-query`)
5. Trigger Firefox certificate acceptance (`https://127.0.0.1:8443/health`)  
   5.1. **DANE Inspector**: Since Firefox doesn't (yet) check TLSA, we implemented a DANE Inspector where URLs can be input to verify TLSA
6. Browse!

## Acknowledgments

This project is inspired by [hnsd](https://github.com/handshake-org/hnsd), the Handshake SPV resolver. We acknowledge the hnsd project contributors and their MIT License.

Special thanks to Nathan and his [DANE checker](https://tools.cn02.woodburn.au/?domain=nathan.woodburn) tool, which provided valuable insights for implementing the DANE Inspector feature.

## License

MIT License - see [LICENSE](LICENSE) file for details.
