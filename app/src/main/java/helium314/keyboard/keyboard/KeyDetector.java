/*
 * Copyright (C) 2010 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard;

import android.graphics.Path;
import android.graphics.Rect;

import java.util.Collections;
import java.util.Map;

/**
 * This class handles key detection.
 */
public class KeyDetector {
    // Expansion at the top edge relative to the bottom, giving a trapezoid that widens downwards.
    private static final float TOP_EXPANSION_RATIO = 0.25f;

    private final int mKeyHysteresisDistanceSquared;
    private final int mKeyHysteresisDistanceForSlidingModifierSquared;

    private Keyboard mKeyboard;
    private int mCorrectionX;
    private int mCorrectionY;
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

    public void setKeyWeights(final Map<Integer, Float> weights) {
        mKeyWeights = weights;
    }

    public void clearKeyWeights() {
        mKeyWeights = Collections.emptyMap();
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
        final float topMarginX = bottomMarginX * TOP_EXPANSION_RATIO;
        final float marginBelow = weight * mKeyboard.mMostCommonKeyHeight;
        final float marginAbove = marginBelow * TOP_EXPANSION_RATIO;
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
            final Rect hitBox = key.getHitBox();
            final float verticalPosition = Math.min(1f, Math.max(0f,
                    (y - hitBox.top) / (float) hitBox.height()));
            final float marginX = weight * keyWidth
                    * (TOP_EXPANSION_RATIO + (1f - TOP_EXPANSION_RATIO) * verticalPosition);
            final float marginY = weight * keyHeight * (y < hitBox.top ? TOP_EXPANSION_RATIO : 1f);
            final int outsideX = Math.max(0, Math.max(hitBox.left - x, x - hitBox.right));
            final int outsideY = Math.max(0, Math.max(hitBox.top - y, y - hitBox.bottom));
            if (outsideX > marginX || outsideY > marginY) {
                continue;
            }
            final float relativeDistance = Math.max(outsideX / marginX, outsideY / marginY);
            final float strength = weight * (1f - relativeDistance);
            if (strength > bestStrength) {
                bestStrength = strength;
                bestKey = key;
            }
        }
        return bestKey;
    }
}
