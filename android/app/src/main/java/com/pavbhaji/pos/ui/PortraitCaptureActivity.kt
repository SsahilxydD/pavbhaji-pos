package com.pavbhaji.pos.ui

import com.journeyapps.barcodescanner.CaptureActivity

// zxing's stock CaptureActivity is landscape-locked; this subclass exists only
// so the manifest can pin the scan screen to portrait like every other screen.
class PortraitCaptureActivity : CaptureActivity()
