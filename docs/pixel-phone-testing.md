# Test Pixel Replica on your phone

Pixel Replica 0.2.0 turns the Pixel 8 into a guided object scanner. Walk around the object; the app takes the photos for you, and a dial shows which sides still need photos. The laptop then turns the photos into a textured 3D model with COLMAP.

## One-time setup

1. **Turn on GitHub Actions for this fork.** GitHub turns it off on forks. Open <https://github.com/lawfan1026/3d-model-anything/actions> and tap **I understand my workflows, go ahead and enable them**. From then on, every push that changes the Android app builds a new APK in about 5–10 minutes.
2. **Uninstall the old Pixel Replica (0.1.x) once.** Long-press its icon → **App info** → **Uninstall**. New builds use a shared signing key that differs from the one on the laptop where 0.1.x was built, so Android won't install over it. Uninstalling deletes the scans stored inside the old app. Photos already copied to the laptop are not affected. To keep an in-app scan, open it and use **Save capture ZIP** first.

## Install or update

On the Pixel, open <https://github.com/lawfan1026/3d-model-anything/releases/tag/pixel-replica-latest> and tap **PixelReplica.apk**. When it downloads, tap **Open**. If Android asks, allow your browser to install unknown apps, then tap **Install**. Google Play Protect may warn about an unknown developer, because this is a test build: tap **More details → Install anyway**.

Later builds install over this one with no uninstall, and your scans are kept.

## Test 1: a five-minute dial check (no laptop)

1. Put the LEGO car on a table in bright, even light. A patterned placemat or newspaper underneath helps the phone track its position.
2. Tap **Start scanning** and move the phone slowly until the top banner stops saying *Move the phone slowly to start tracking*.
3. Hold the phone at about the car's height and walk slowly around it, keeping the car in the white centre circle. When the banner says **New view: pause here**, stop for about a second. The phone buzzes when a photo is saved and that sector of the dial turns green.
4. The yellow sector is the view you are on. Sectors on the dial's right are reached by walking to your right.

Things to tell Claude: did tracking start? Did photos capture on their own, and roughly how long did each pause take? Did any *may be soft* messages appear in the yellow line under the camera? Did the dial's left/right match your walking?

## Test 2: a full scan for the laptop

1. Do three laps: **low** (phone at the car's height), **middle** (raise the phone and look down about 35°), **high** (look down steeply). Finish with one photo straight down. The banner tells you when to change height. This takes about 60 photos and 5–10 minutes.
2. Don't touch or move the car, or bump the table. To scan the underside later, tap **New pass**, turn the car over, and repeat the laps.
3. Optional: switch on **Holdout photo**, tap **Capture** from 2–4 different sides, then switch it off. Holdouts are kept out of the model and used to check it.
4. Tap **Done**. Optional: **Add physical measurement**, for example the car's overall length from a ruler, with role *check*.
5. Tap **Save capture ZIP** and copy it to the laptop: USB with *File transfer*, or **Share capture ZIP** to Quick Share or Google Drive.
6. On the laptop, run `Start-PixelReplica.cmd`, choose **Import scan**, then **Reconstruct object**.

The laptop now estimates real-world size automatically from the phone's motion tracking, usually within a few percent. A ruler *check* measurement shows how far off that estimate is, and a ruler *scale* measurement replaces it.

## Tips for LEGO

- Glossy plastic is the hardest part. Avoid lamps reflecting straight off the car; light bounced off a wall or a bright overcast window works best.
- Stay roughly 25–40 cm away. Much closer and focus struggles; much farther and you lose detail.
- Don't zoom. **Lock colour** keeps white balance the same across photos for even textures. Turn it on once the image looks right. It hasn't been tried on the Pixel yet, so mention it if colours come out wrong.
- Black tyres, shiny chrome and transparent windows reconstruct poorly in any photogrammetry tool.

## If something goes wrong

Take a screenshot of the capture screen, including the small grey status text and the yellow message line under the camera view, and send it to Claude along with what you were doing.
