import UIKit
import Capacitor

/// App-local Capacitor plugins (NorisugoshiTracker) must be registered on the bridge —
/// this subclass is wired up as the storyboard's view-controller class (Main.storyboard
/// customClass), replacing the stock CAPBridgeViewController.
class MainViewController: CAPBridgeViewController {
    override open func capacitorDidLoad() {
        bridge?.registerPluginInstance(NorisugoshiTrackerPlugin())
    }
}
