/*
 * Copyright (C) 2010 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard;

import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;

import helium314.keyboard.latin.common.Constants;
import helium314.keyboard.latin.settings.Settings;

import java.util.Collections;
import java.util.Map;

/**
 * This class handles key detection.
 */
public class KeyDetector {
    private static final int OVERLAP_SAMPLES_PER_AXIS = 15;
    private static final float CORNER_RADIUS_RATIO = 0.3f;
    // Lobe tip half width and notch depth between the lobes, relative to the lobe length.
    private static final float LOBE_TIP_RATIO = 0.35f;
    private static final float NOTCH_DEPTH_RATIO = 0.2f;
    // Where on the key's sides the lobes start, as fraction of key height from the top.
    private static final float LOBE_SHOULDER_RATIO = 0.33f;
    private static final int DOME_SEGMENTS = 8;

    private final int mKeyHysteresisDistanceSquared;
    private final int mKeyHysteresisDistanceForSlidingModifierSquared;

    private Keyboard mKeyboard;
    private int mCorrectionX;
    private int mCorrectionY;
    // lowercase code point -> probability of being typed next
    private Map<Integer, Float> mKeyProbabilities = Collections.emptyMap();
    // lowercase code point -> expansion as fraction of the most common key size
    private Map<Integer, Float> mKeyWeights = Collections.emptyMap();
    private Map<Key, Region> mKeyRegions = Collections.emptyMap();
    private Map<Key, Path> mKeyPaths = Collections.emptyMap();
    private float mMaxStretchX = 1f;
    private float mMaxStretchY = 1f;

    public KeyDetector() {
        this(0.0f /* keyHysteresisDistance */, 0.0f /* keyHysteresisDistanceForSlidingModifier */);
    }

    /**
     * Key detection object constructor with key hysteresis distances.
     *
     * @param keyHysteresisDistance if the pointer movement distance is smaller than this, the
     * movement will not be handled as meaningful movement. The unit is pixel.
     * @param keyHysteresisDistanceForSlidingModifier the same parameter for sliding input that
     * starts from a modifier key such as shift and symbols key.
     */
    public KeyDetector(final float keyHysteresisDistance,
            final float keyHysteresisDistanceForSlidingModifier) {
        mKeyHysteresisDistanceSquared = (int)(keyHysteresisDistance * keyHysteresisDistance);
        mKeyHysteresisDistanceForSlidingModifierSquared = (int)(
                keyHysteresisDistanceForSlidingModifier * keyHysteresisDistanceForSlidingModifier);
    }

    public void setKeyboard(final Keyboard keyboard, final float correctionX,
            final float correctionY) {
        if (keyboard == null) {
            throw new NullPointerException();
        }
        mCorrectionX = (int)correctionX;
        mCorrectionY = (int)correctionY;
        mKeyboard = keyboard;
        updateKeyWeights();
    }

    public int getKeyHysteresisDistanceSquared(final boolean isSlidingFromModifier) {
        return isSlidingFromModifier
                ? mKeyHysteresisDistanceForSlidingModifierSquared : mKeyHysteresisDistanceSquared;
    }

    public int getTouchX(final int x) {
        return x + mCorrectionX;
    }

    // TODO: Remove vertical correction.
    public int getTouchY(final int y) {
        return y + mCorrectionY;
    }

    public Keyboard getKeyboard() {
        return mKeyboard;
    }

    public boolean alwaysAllowsKeySelectionByDraggingFinger() {
        return false;
    }

    public void setKeyProbabilities(final Map<Integer, Float> probabilities) {
        mKeyProbabilities = probabilities;
        updateKeyWeights();
    }

    public void clearKeyWeights() {
        mKeyProbabilities = Collections.emptyMap();
        mKeyWeights = Collections.emptyMap();
        mKeyRegions = Collections.emptyMap();
        mKeyPaths = Collections.emptyMap();
    }

    /**
     * Isolated predicted keys get the full growth; predicted keys whose enlarged regions could touch
     * share it according to probability, relative to the most probable key among them.
     */
    private void updateKeyWeights() {
        final float scale = Settings.getValues().mPredictiveKeyHitbox;
        if (mKeyboard == null || mKeyProbabilities.isEmpty() || scale <= 0f) {
            mKeyWeights = Collections.emptyMap();
            mKeyRegions = Collections.emptyMap();
            mKeyPaths = Collections.emptyMap();
            return;
        }
        final int keyWidth = mKeyboard.mMostCommonKeyWidth;
        final int keyHeight = mKeyboard.mMostCommonKeyHeight;
        // A lobe reaches at most twice the weight beyond the original key.
        mMaxStretchX = 3f * scale * keyWidth;
        mMaxStretchY = 2 * scale * keyHeight;

        final float minProbability = Settings.getValues().mPredictiveKeyMinProbability;
        final java.util.ArrayList<Key> predictedKeys = new java.util.ArrayList<>();
        for (final Key key : mKeyboard.getSortedKeys()) {
            final float probability = getKeyProbability(key);
            if (probability > 0f && probability >= minProbability) predictedKeys.add(key);
        }
        final float maxGapX = 2 * mMaxStretchX;
        final float maxGapY = 2 * mMaxStretchY;
        final java.util.HashMap<Key, Float> keyWeights = new java.util.HashMap<>();
        final java.util.HashMap<Integer, Float> weights = new java.util.HashMap<>();
        for (final Key key : predictedKeys) {
            final float probability = getKeyProbability(key);
            float maxNearbyProbability = probability;
            final Rect hitBox = key.getHitBox();
            for (final Key other : predictedKeys) {
                final Rect otherHitBox = other.getHitBox();
                final int gapX = Math.max(otherHitBox.left - hitBox.right, hitBox.left - otherHitBox.right);
                final int gapY = Math.max(otherHitBox.top - hitBox.bottom, hitBox.top - otherHitBox.bottom);
                if (gapX <= maxGapX && gapY <= maxGapY) {
                    maxNearbyProbability = Math.max(maxNearbyProbability, getKeyProbability(other));
                }
            }
            final float weight = scale * probability / maxNearbyProbability;
            keyWeights.put(key, weight);
            final int code = Character.toLowerCase(key.getCode());
            final Float previous = weights.get(code);
            weights.put(code, previous == null ? weight : Math.max(previous, weight));
        }

        final java.util.HashMap<Key, Region> regions = new java.util.HashMap<>();
        final java.util.HashMap<Key, Path> paths = new java.util.HashMap<>();
        final RectF bounds = new RectF();
        for (final Map.Entry<Key, Float> entry : keyWeights.entrySet()) {
            final Key key = entry.getKey();
            final Path path = buildKeyRegionPath(key.getHitBox(), entry.getValue(),
                    key.getCode() == Constants.CODE_SPACE);
            path.computeBounds(bounds, true);
            final Region region = new Region();
            region.setPath(path, new Region((int) Math.floor(bounds.left) - 1, (int) Math.floor(bounds.top) - 1,
                    (int) Math.ceil(bounds.right) + 1, (int) Math.ceil(bounds.bottom) + 1));
            regions.put(entry.getKey(), region);
            paths.put(entry.getKey(), path);
        }
        mKeyWeights = weights;
        mKeyRegions = regions;
        mKeyPaths = paths;
    }

    /**
     * The key with two lobes growing out of its lower sides, one towards each bottom corner of the
     * keyboard, and a notch between them below the key. The original key area is always kept.
     * If {@code domed}, there are no lobes and the top edge bulges upwards, highest in the center.
     */
    private Path buildKeyRegionPath(final Rect hitBox, final float weight, final boolean domed) {
        final float keyWidth = mKeyboard.mMostCommonKeyWidth;
        final float keyHeight = mKeyboard.mMostCommonKeyHeight;
        final float keyboardWidth = mKeyboard.mOccupiedWidth;
        final float keyboardHeight = mKeyboard.mOccupiedHeight;
        final float lobeLength = 2 * weight * keyWidth;
        final float tipHalfWidth = LOBE_TIP_RATIO * lobeLength;
        final float shoulderY = hitBox.top + LOBE_SHOULDER_RATIO * hitBox.height();

        // right lobe towards bottom right corner; outward normal points up and right
        float dx = keyboardWidth - hitBox.right;
        float dy = keyboardHeight - hitBox.bottom;
        float length = Math.max(1f, (float) Math.hypot(dx, dy));
        dx /= length;
        dy /= length;
        final float rightTipX = hitBox.right + dx * lobeLength;
        final float rightTipY = hitBox.bottom + dy * lobeLength;
        final float rightOuterX = rightTipX + dy * tipHalfWidth;
        final float rightOuterY = rightTipY - dx * tipHalfWidth;
        final float rightInnerX = rightTipX - dy * tipHalfWidth;
        final float rightInnerY = Math.max(hitBox.bottom, rightTipY + dx * tipHalfWidth);

        // left lobe towards bottom left corner; outward normal points up and left
        dx = -hitBox.left;
        dy = keyboardHeight - hitBox.bottom;
        length = Math.max(1f, (float) Math.hypot(dx, dy));
        dx /= length;
        dy /= length;
        final float leftTipX = hitBox.left + dx * lobeLength;
        final float leftTipY = hitBox.bottom + dy * lobeLength;
        final float leftOuterX = leftTipX - dy * tipHalfWidth;
        final float leftOuterY = leftTipY + dx * tipHalfWidth;
        final float leftInnerX = leftTipX + dy * tipHalfWidth;
        final float leftInnerY = Math.max(hitBox.bottom, leftTipY - dx * tipHalfWidth);

        final float notchY = hitBox.bottom + NOTCH_DEPTH_RATIO * lobeLength;
        final int topPoints = domed ? DOME_SEGMENTS + 1 : 2;
        final float domeHeight = 2 * weight * keyHeight;
        final float[] restXs = domed
                ? new float[] { hitBox.right, hitBox.left }
                : new float[] { hitBox.right, rightOuterX, rightInnerX, hitBox.exactCenterX(), leftInnerX, leftOuterX, hitBox.left };
        final float[] restYs = domed
                ? new float[] { hitBox.bottom, hitBox.bottom }
                : new float[] { shoulderY, rightOuterY, rightInnerY, notchY, leftInnerY, leftOuterY, shoulderY };
        final float[] xs = new float[topPoints + restXs.length];
        final float[] ys = new float[topPoints + restYs.length];
        // clockwise from top left; the dome is a parabola over the top edge
        for (int i = 0; i < topPoints; i++) {
            final float t = i / (float) (topPoints - 1);
            xs[i] = hitBox.left + t * hitBox.width();
            ys[i] = hitBox.top - (domed ? domeHeight * 4 * t * (1 - t) : 0f);
        }
        System.arraycopy(restXs, 0, xs, topPoints, restXs.length);
        System.arraycopy(restYs, 0, ys, topPoints, restYs.length);
        return roundedPolygon(xs, ys, CORNER_RADIUS_RATIO * Math.min(keyWidth, keyHeight));
    }

    private static Path roundedPolygon(final float[] xs, final float[] ys, final float radius) {
        final Path path = new Path();
        final int n = xs.length;
        for (int i = 0; i < n; i++) {
            final int prev = (i + n - 1) % n;
            final int next = (i + 1) % n;
            final float prevLength = (float) Math.hypot(xs[prev] - xs[i], ys[prev] - ys[i]);
            final float nextLength = (float) Math.hypot(xs[next] - xs[i], ys[next] - ys[i]);
            final float startFraction = prevLength > 0f ? Math.min(radius, prevLength / 2) / prevLength : 0f;
            final float endFraction = nextLength > 0f ? Math.min(radius, nextLength / 2) / nextLength : 0f;
            final float startX = xs[i] + (xs[prev] - xs[i]) * startFraction;
            final float startY = ys[i] + (ys[prev] - ys[i]) * startFraction;
            if (i == 0) path.moveTo(startX, startY);
            else path.lineTo(startX, startY);
            path.quadTo(xs[i], ys[i], xs[i] + (xs[next] - xs[i]) * endFraction,
                    ys[i] + (ys[next] - ys[i]) * endFraction);
        }
        path.close();
        return path;
    }

    public float getKeyProbability(final Key key) {
        final Float probability = mKeyProbabilities.get(Character.toLowerCase(key.getCode()));
        return probability == null ? 0f : probability;
    }

    private float getKeyWeight(final Key key) {
        final Float weight = mKeyWeights.get(Character.toLowerCase(key.getCode()));
        return weight == null ? 0f : weight;
    }

    /**
     * Sets {@code out} to the outline of the region a weighted key claims, in view coordinates.
     * @return the key weight, or 0 if the key has no weight (and {@code out} is untouched).
     */
    public float getWeightedKeyRegion(final Key key, final Path out) {
        final Path path = mKeyPaths.get(key);
        if (path == null) {
            return 0f;
        }
        out.set(path);
        out.offset(-mCorrectionX, -mCorrectionY);
        return getKeyWeight(key);
    }

    /**
     * Picks the key whose hit region, including enlargement of predicted keys, overlaps most with the
     * touch ellipse. The ellipse uses the same convention as {@link android.view.MotionEvent#getOrientation}.
     */
    public Key detectHitKeyByOverlap(final int x, final int y, final float halfMajor,
            final float halfMinor, final float orientation) {
        if (mKeyboard == null || halfMajor < 1f) {
            return detectHitKey(x, y);
        }
        final float cos = (float) Math.cos(orientation);
        final float sin = (float) Math.sin(orientation);
        final java.util.HashMap<Key, Integer> overlaps = new java.util.HashMap<>();
        for (int i = 0; i < OVERLAP_SAMPLES_PER_AXIS; i++) {
            final float u = (2f * i + 1f) / OVERLAP_SAMPLES_PER_AXIS - 1f;
            for (int j = 0; j < OVERLAP_SAMPLES_PER_AXIS; j++) {
                final float v = (2f * j + 1f) / OVERLAP_SAMPLES_PER_AXIS - 1f;
                if (u * u + v * v > 1f) continue;
                final float localX = u * halfMinor;
                final float localY = v * halfMajor;
                final Key key = detectHitKey(Math.round(x + localX * cos - localY * sin),
                        Math.round(y + localX * sin + localY * cos));
                if (key != null) overlaps.merge(key, 1, Integer::sum);
            }
        }
        Key bestKey = null;
        int bestOverlap = 0;
        for (final java.util.Map.Entry<Key, Integer> entry : overlaps.entrySet()) {
            if (entry.getValue() > bestOverlap) {
                bestOverlap = entry.getValue();
                bestKey = entry.getKey();
            }
        }
        return bestKey != null ? bestKey : detectHitKey(x, y);
    }

    /**
     * Detect the key whose hitbox the touch point is in.
     *
     * @param x The x-coordinate of a touch point
     * @param y The y-coordinate of a touch point
     * @return the key that the touch point hits.
     */
    public Key detectHitKey(final int x, final int y) {
        if (mKeyboard == null) {
            return null;
        }
        final int touchX = getTouchX(x);
        final int touchY = getTouchY(y);

        int minDistance = Integer.MAX_VALUE;
        Key primaryKey = null;
        for (final Key key: mKeyboard.getNearestKeys(touchX, touchY)) {
            // An edge key always has its enlarged hitbox to respond to an event that occurred in
            // the empty area around the key. (@see Key#markAsLeftEdge(KeyboardParams)} etc.)
            if (!key.isOnKey(touchX, touchY)) {
                continue;
            }
            final int distance = key.squaredDistanceToEdge(touchX, touchY);
            if (distance > minDistance) {
                continue;
            }
            // To take care of hitbox overlaps, we compare key's code here too.
            if (primaryKey == null || distance < minDistance
                    || key.getCode() > primaryKey.getCode()) {
                minDistance = distance;
                primaryKey = key;
            }
        }
        if (mKeyWeights.isEmpty()) {
            return primaryKey;
        }
        return detectWeightedKey(touchX, touchY, primaryKey);
    }

    /**
     * Lets predicted keys claim touches within their enlarged, dragged region. A claiming key wins
     * over the key actually hit if its weight, reduced by how far out the touch is, exceeds the hit
     * key's weight.
     */
    private Key detectWeightedKey(final int x, final int y, final Key primaryKey) {
        Key bestKey = primaryKey;
        float bestStrength = primaryKey == null ? 0f : getKeyWeight(primaryKey);
        for (final Map.Entry<Key, Region> entry : mKeyRegions.entrySet()) {
            final Key key = entry.getKey();
            if (key == primaryKey || !entry.getValue().contains(x, y)) {
                continue;
            }
            final Rect hitBox = key.getHitBox();
            final int outsideX = Math.max(0, Math.max(hitBox.left - x, x - hitBox.right));
            final int outsideY = Math.max(0, Math.max(hitBox.top - y, y - hitBox.bottom));
            // capped below 1 so that every point inside the region can beat an unpredicted key
            final float relativeDistance = Math.min(0.95f,
                    Math.max(outsideX / mMaxStretchX, outsideY / mMaxStretchY));
            final float strength = getKeyWeight(key) * (1f - relativeDistance);
            if (strength > bestStrength) {
                bestStrength = strength;
                bestKey = key;
            }
        }
        return bestKey;
    }
}
