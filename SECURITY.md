# Security

Please report security vulnerabilities through GitHub's private security-advisory feature rather than a public issue. Include the affected version, Fire OS or Android TV version, reproduction steps and any relevant logs with personal data removed.

Do not attach account tokens, signing keys, private TV backups or unredacted notification contents.

YouTube cookies, `youtube.identity.json`, bridge access keys, `.env` files, browser HAR captures and the complete `youtube-bridge/data/` directory are credentials or sensitive runtime data. Never commit them, attach them to an issue or paste them into a discussion. The repository ignores these paths, but contributors must still inspect staged changes before pushing.

The bridge is designed for a trusted private network. Do not expose port 8787 directly to the Internet. Revoke the browser session and replace the bridge key immediately if either credential may have leaked.
