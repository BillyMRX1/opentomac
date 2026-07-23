# homebrew-opentomac

Homebrew tap for opentomac (`billymrx1/opentomac`). The cask lives at
`Casks/opentomac.rb`; its source of truth is `packaging/homebrew/Casks/opentomac.rb`
in the main repo.

## Install

```
brew install --cask --no-quarantine billymrx1/opentomac/opentomac
```

The `--no-quarantine` flag is required because opentomac releases are unsigned and
un-notarized (the project does not yet pay for the Apple Developer Program). Without
it, macOS Gatekeeper blocks the first launch.

## Update

Run `./scripts/update-cask.sh <tag>` from the main repo to regenerate the cask for a
new release, then copy `Casks/opentomac.rb` here and push.

See the main repo: https://github.com/BillyMRX1/opentomac
