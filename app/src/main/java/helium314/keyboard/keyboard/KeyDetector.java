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

import helium314.keyboard.latin.settings.Settings;

import java.util.Collections;
import java.util.Map;

/**
 * This class handles key detection.
 */
public class KeyDetector {
    private static final int OVERLAP_SAMPLES_PER_AXIS = 15;
    private static final float CORNER_RADIUS_RATIO = 0.3f;
    // Top corners are pulled less than bottom corners, as fingers come from below.
    private static final float TOP_PULL_RATIO = 0.95f;

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
        final float drag = Settings.getValues().mPredictiveKeyDrag;
        final int keyWidth = mKeyboard.mMostCommonKeyWidth;
        final int keyHeight = mKeyboard.mMostCommonKeyHeight;
        // Dragging can stretch the region beyond the plain enlargement, up to this limit.
        final float maxStretch = scale * (1f + drag);
        mMaxStretchX = maxStretch * keyWidth;
        mMaxStretchY = maxStretch * keyHeight;

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
            final Path path = buildKeyRegionPath(entry.getKey().getHitBox(), entry.getValue(), drag);
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
     * Enlarges the hitbox by the weight, then drags left corners towards the bottom left and right
     * corners towards the bottom right of the keyboard, pulling harder the further away they are
     * (top corners less). The region is a rubber band stretched around the dragged corners and the
     * original hitbox corners, so the original key area is always kept.
     */
    private Path buildKeyRegionPath(final Rect hitBox, final float weight, final float drag) {
        final float keyWidth = mKeyboard.mMostCommonKeyWidth;
        final float keyHeight = mKeyboard.mMostCommonKeyHeight;
        final float keyboardWidth = Math.max(1, mKeyboard.mOccupiedWidth);
        final float keyboardHeight = Math.max(1, mKeyboard.mOccupiedHeight);
        final float pullX = 2f * drag * weight * keyWidth / keyboardWidth;
        final float pullY = 2f * drag * weight * keyHeight / keyboardHeight;
        final float topPullX = pullX * TOP_PULL_RATIO;
        final float topPullY = pullY * TOP_PULL_RATIO;

        final float left = hitBox.left - weight * keyWidth;
        final float right = hitBox.right + weight * keyWidth;
        final float top = hitBox.top - weight * keyHeight;
        final float bottom = hitBox.bottom + weight * keyHeight;
        final float minLeft = hitBox.left - mMaxStretchX;
        final float maxRight = hitBox.right + mMaxStretchX;
        final float maxBottom = hitBox.bottom + mMaxStretchY;

        // dragged top left, top right, bottom right, bottom left, then the original hitbox corners
        final float[] xs = {
                Math.max(minLeft, left - topPullX * left),
                Math.min(maxRight, right + topPullX * (keyboardWidth - right)),
                Math.min(maxRight, right + pullX * (keyboardWidth - right)),
                Math.max(minLeft, left - pullX * left),
                hitBox.left, hitBox.right, hitBox.right, hitBox.left
        };
        final float[] ys = {
                Math.min(maxBottom, top + topPullY * (keyboardHeight - top)),
                Math.min(maxBottom, top + topPullY * (keyboardHeight - top)),
                Math.min(maxBottom, bottom + pullY * (keyboardHeight - bottom)),
                Math.min(maxBottom, bottom + pullY * (keyboardHeight - bottom)),
                hitBox.top, hitBox.top, hitBox.bottom, hitBox.bottom
        };
        final int[] hull = convexHull(xs, ys);
        final float[] hullXs = new float[hull.length];
        final float[] hullYs = new float[hull.length];
        for (int i = 0; i < hull.length; i++) {
            hullXs[i] = xs[hull[i]];
            hullYs[i] = ys[hull[i]];
        }
        return roundedPolygon(hullXs, hullYs, CORNER_RADIUS_RATIO * Math.min(keyWidth, keyHeight));
    }

    /** Andrew's monotone chain; returns indices of hull vertices in order, without collinear points. */
    private static int[] convexHull(final float[] xs, final float[] ys) {
        final int n = xs.length;
        final Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> xs[a] != xs[b] ? Float.compare(xs[a], xs[b]) : Float.compare(ys[a], ys[b]));
        final int[] hull = new int[2 * n];
        int size = 0;
        for (int pass = 0; pass < 2; pass++) {
            final int start = size;
            for (int k = 0; k < n; k++) {
                final int p = order[pass == 0 ? k : n - 1 - k];
                while (size >= start + 2 && cross(xs, ys, hull[size - 2], hull[size - 1], p) <= 0f) size--;
                hull[size++] = p;
            }
            size--; // last point is the first point of the other chain
        }
        return java.util.Arrays.copyOf(hull, Math.max(size, 0));
    }

    private static float cross(final float[] xs, final float[] ys, final int o, final int a, final int b) {
        return (xs[a] - xs[o]) * (ys[b] - ys[o]) - (ys[a] - ys[o]) * (xs[b] - xs[o]);
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
