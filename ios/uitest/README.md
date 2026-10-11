# KidVid iOS simulator swipe test

Reproduces / guards the "screen goes black after swiping" bug.

```bash
cd ios/uitest
xcodegen generate --spec project-uitest.yml          # -> KidVidUITest.xcodeproj (git-ignored)
# seed the simulator's app container with some mp4s first (see scripts/sim-swipe-test.sh)
xcodebuild test -project KidVidUITest.xcodeproj -scheme KidVidUITests \
  -destination 'platform=iOS Simulator,name=KidVid SE3'
```

The test swipes up/down (single swipes and quick bursts), screenshots the
screen at several delays after each swipe and counts frames where the video
area is pure black. Screenshots + `summary.txt` land in `$KV_OUT`
(default `/tmp/kidvid-uitest`). It fails if any frame >=0.6s after a swipe
is still black.
