# homebrew-opentomac

Homebrew tap for opentomac (`billymrx1/opentomac`). The cask lives at
`Casks/opentomac.rb`; its source of truth is `packaging/homebrew/Casks/opentomac.rb`
in the main repo.

## Install

```
brew install --cask billymrx1/opentomac/opentomac
xattr -d com.apple.quarantine /Applications/Opentomac.app
```

The `xattr` line is needed because opentomac releases are unsigned and un-notarized
(the project does not yet pay for the Apple Developer Program): without it, macOS
Gatekeeper blocks the first launch. Homebrew 6 removed the old `--no-quarantine`
install flag, so the quarantine attribute must be cleared manually. Alternatively,
approve the app once via System Settings > Privacy & Security > Open Anyway.

## Update

Run `./scripts/update-cask.sh <tag>` from the main repo to regenerate the cask for a
new release, then copy `Casks/opentomac.rb` here and push.

See the main repo: https://github.com/BillyMRX1/opentomac
