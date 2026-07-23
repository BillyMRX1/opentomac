cask "opentomac" do
  version "0.2.1"
  sha256 "32f7a96943f5141f0908bd8be866ee2f06f3b40383d61d56800f92c160257f25"

  url "https://github.com/BillyMRX1/opentomac/releases/download/v#{version}/opentomac-v#{version}-macos.dmg"
  name "opentomac"
  desc "Local-first continuity app for pairing with an Android phone"
  homepage "https://github.com/BillyMRX1/opentomac"

  livecheck do
    url :url
    strategy :github_latest
  end

  depends_on arch: :arm64
  depends_on macos: :sonoma

  app "Opentomac.app"

  zap trash: [
    "~/Library/Application Support/opentomac",
    "~/Library/Preferences/dev.opentomac.mac.plist",
    "~/Library/Saved Application State/dev.opentomac.mac.savedState",
  ]

  caveats do
    <<~EOS
      opentomac releases are not signed or notarized. Install with
      --no-quarantine to skip the Gatekeeper prompt:
        brew install --cask --no-quarantine billymrx1/opentomac/opentomac
      If already installed without the flag, approve the app once via
      System Settings > Privacy & Security > Open Anyway.
    EOS
  end
end
