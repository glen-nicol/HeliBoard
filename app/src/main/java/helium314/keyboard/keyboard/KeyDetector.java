/*
 * Copyright (C) 2010 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard;

import android.graphics.Path;
import android.graphics.Rect;

import helium314.keyboard.latin.settings.Settings;

import java.util.Collections;
import java.util.Map;

/**
 * This class handles key detection.
 */
public class KeyDetector {
    private static final int OVERLAP_SAMPLES_PER_AXIS = 15;

    private final int mKeyHysteresisDistanceSquared;
    private final int mKeyHysteresisDistanceForSlidingModifierSquared;

    private Keyboard mKeyboard;
    private int mCorrectionX;
    private int mCorrectionY;
    // lowercase code point -> probability of being typed next
    private Map<Integer, Float> mKeyProbabilities = Collections.emptyMap();
    // lowercase code point -> expansion as fraction of the most common key size
    private Map<Integer, Float> mKeyWeights = Collections.emptyMap();

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
    }

    /**
     * Isolated predicted keys get the full growth; predicted keys whose enlarged regions could touch
     * share it according to probability, relative to the most probable key among them.
     */
    private void updateKeyWeights() {
        final float scale = Settings.getValues().mPredictiveKeyHitbox;
        if (mKeyboard == null || mKeyProbabilities.isEmpty() || scale <= 0f) {
            mKeyWeights = Collections.emptyMap();
            return;
        }
        final float minProbability = Settings.getValues().mPredictiveKeyMinProbability;
        final java.util.ArrayList<Key> predictedKeys = new java.util.ArrayList<>();
        for (final Key key : mKeyboard.getSortedKeys()) {
            final float probability = getKeyProbability(key);
            if (probability > 0f && probability >= minProbability) predictedKeys.add(key);
        }
        final float maxGapX = 2 * scale * mKeyboard.mMostCommonKeyWidth;
        final float maxGapY = 2 * scale * mKeyboard.mMostCommonKeyHeight;
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
            final int code = Character.toLowerCase(key.getCode());
            final float weight = scale * probability / maxNearbyProbability;
            final Float previous = weights.get(code);
            weights.put(code, previous == null ? weight : Math.max(previous, weight));
        }
        mKeyWeights = weights;
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
        final float weight = mKeyboard == null ? 0f : getKeyWeight(key);
        if (weight <= 0f) {
            return 0f;
        }
        final Rect hitBox = key.getHitBox();
        final float bottomMarginX = weight * mKeyboard.mMostCommonKeyWidth;
        final float topRatio = Settings.getValues().mPredictiveKeyHitboxTopRatio;
        final float topMarginX = bottomMarginX * topRatio;
        final float marginBelow = weight * mKeyboard.mMostCommonKeyHeight;
        final float marginAbove = marginBelow * Settings.getValues().mPredictiveKeyHitboxUpRatio;
        final float left = hitBox.left - mCorrectionX;
        final float right = hitBox.right - mCorrectionX;
        final float top = hitBox.top - mCorrectionY;
        final float bottom = hitBox.bottom - mCorrectionY;
        out.reset();
        out.moveTo(left - topMarginX, top - marginAbove);
        out.lineTo(right + topMarginX, top - marginAbove);
        out.lineTo(right + topMarginX, top);
        out.lineTo(right + bottomMarginX, bottom);
        out.lineTo(right + bottomMarginX, bottom + marginBelow);
        out.lineTo(left - bottomMarginX, bottom + marginBelow);
        out.lineTo(left - bottomMarginX, bottom);
        out.lineTo(left - topMarginX, top);
        out.close();
        return weight;
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
     * Lets keys with a weight claim touches within a trapezoid around their hitbox that widens
     * towards the bottom, since thumbs approach from below. A claiming key wins over the key
     * actually hit if its weight, reduced by how far out the touch is, exceeds the hit key's weight.
     */
    private Key detectWeightedKey(final int x, final int y, final Key primaryKey) {
        final int keyWidth = mKeyboard.mMostCommonKeyWidth;
        final int keyHeight = mKeyboard.mMostCommonKeyHeight;
        Key bestKey = primaryKey;
        float bestStrength = primaryKey == null ? 0f : getKeyWeight(primaryKey);
        for (final Key key : mKeyboard.getNearestKeys(x, y)) {
            if (key == primaryKey) {
                continue;
            }
            final float weight = getKeyWeight(key);
            if (weight <= 0f) {
                continue;
            }
            final float topRatio = Settings.getValues().mPredictiveKeyHitboxTopRatio;
            final Rect hitBox = key.getHitBox();
            final float verticalPosition = Math.min(1f, Math.max(0f,
                    (y - hitBox.top) / (float) hitBox.height()));
            final float marginX = weight * keyWidth
                    * (topRatio + (1f - topRatio) * verticalPosition);
            final float marginY = weight * keyHeight
                    * (y < hitBox.top ? Settings.getValues().mPredictiveKeyHitboxUpRatio : 1f);
            final int outsideX = Math.max(0, Math.max(hitBox.left - x, x - hitBox.right));
            final int outsideY = Math.max(0, Math.max(hitBox.top - y, y - hitBox.bottom));
            if (outsideX > marginX || outsideY > marginY) {
                continue;
            }
            final float relativeDistance = Math.max(marginX > 0f ? outsideX / marginX : 0f,
                    marginY > 0f ? outsideY / marginY : 0f);
            final float strength = weight * (1f - relativeDistance);
            if (strength > bestStrength) {
                bestStrength = strength;
                bestKey = key;
            }
        }
        return bestKey;
    }
}
