/*
 * This file is part of las-vpe-platform.
 *
 * las-vpe-platform is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * las-vpe-platform is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with las-vpe-platform. If not, see <http://www.gnu.org/licenses/>.
 *
 * Created by ken.yu on 17-3-25.
 */
package org.cripac.isee.alg.pedestrian.attr;

import com.google.gson.Gson;
import org.apache.log4j.Logger;
import org.bytedeco.javacpp.*;
import org.cripac.isee.alg.pedestrian.tracking.Tracklet;

import javax.annotation.Nonnull;

import java.util.Random;

import static org.bytedeco.javacpp.opencv_core.*;

/**
 * The interface DeepMAR defines some universal parameters and actions used by any DeepMAR implementations.
 */
public interface DeepMAR extends Recognizer {
    float MEAN_PIXEL = 128;
    float REG_COEFF = 1.0f / 256;
    Random random = new Random(System.currentTimeMillis());

    static int randomlyPickGPU(String gpus) {
        String[] gpuIDs = gpus.split(",");
        return Integer.parseInt(gpuIDs[random.nextInt(gpuIDs.length)]);
    }

    /**
     * The class PointerManager holds the pointers to some constant values.
     * It deallocates the pointers on destruction.
     */
    class PointerManager {
        static {
            Loader.load(opencv_core.class);
        }

        FloatPointer pMean32f;
        FloatPointer pRegCoeff;
        DoublePointer pScale;

        /**
         * Create the pointers.
         */
        PointerManager() {
            pMean32f = new FloatPointer(MEAN_PIXEL);
            pRegCoeff = new FloatPointer(REG_COEFF);
            pScale = new DoublePointer(1.);
        }

        /**
         * Deallocate the pointers.
         *
         * @throws Throwable on failure finalizing the super class.
         */
        @Override
        protected void finalize() throws Throwable {
            pMean32f.deallocate();
            pRegCoeff.deallocate();
            pScale.deallocate();
            super.finalize();
        }
    }

    PointerManager POINTERS = new PointerManager();

    int INPUT_WIDTH = 227;
    int INPUT_HEIGHT = 227;

    /**
     * Preprocess the image, including mean value subtracting, value normalizing and pixel remapping.
     *
     * @param bbox the bounding box including the target pedestrian image.
     * @return the preprocessed pixel array (three channels lined in order).
     */
    static @Nonnull
    float[] preprocess(@Nonnull Tracklet.BoundingBox bbox) {
        // Process image.
        opencv_core.Mat image = bbox.getImage();
        opencv_imgproc.resize(image, image, new opencv_core.Size(INPUT_WIDTH, INPUT_HEIGHT));
        image.convertTo(image, CV_32FC3);

        // Regularize pixel values.
        final int numPixelPerChannel = image.rows() * image.cols();
        final int numPixels = numPixelPerChannel * 3;
        final FloatPointer imgData = new FloatPointer(image.data());

//        float[] origin = new float[numPixels];
//        imgData.get(origin);
//        for (int i = 0; i < numPixels; ++i) {
//            origin[i] = (origin[i] - 128) / 256;
//        }
//        imgData.put(origin);
        // Subtract mean pixel.
        sub32f(imgData, // Pointer to minuends
                4, // Bytes per step (4 bytes for float)
                POINTERS.pMean32f, // Pointer to subtrahend
                0, // Bytes per step (using the value 128 circularly)
                imgData, // Pointer to result buffer.
                4, // Bytes per step (4 bytes for float)
                1, numPixels, // Data dimensions.
                null);
        // Regularize to -0.5 to 0.5. The additional scaling is disabled (set to 1).
        mul32f(imgData, 4, POINTERS.pRegCoeff, 0, imgData, 4, 1, numPixels, POINTERS.pScale);

        //Slice into channels.
        MatVector bgr = new MatVector(3);
        split(image, bgr);
        // Get pixel data by channel.
        final float[] pixelFloats = new float[numPixelPerChannel * 3];
        for (int i = 0; i < 3; ++i) {
            final FloatPointer fp = new FloatPointer(bgr.get(i).data());
            fp.get(pixelFloats, i * numPixelPerChannel, numPixelPerChannel);
            fp.deallocate();
        }
        imgData.deallocate();
        image.deallocate();

        return pixelFloats;
    }

    /**
     * Fill the values from the FC8 layer of DeepMAR into an Attributes object.
     *
     * @param outputArray vector from the FC8 layer.
     * @return attributes.
     */
    @Nonnull
    static Attributes fillAttributes(@Nonnull float[] outputArray) {
        int iter = 0;
        StringBuilder jsonBuilder = new StringBuilder();
        jsonBuilder.append('{');
        for (String attr : ATTR_LIST) {
            jsonBuilder.append('\"').append(attr).append('\"').append('=').append(outputArray[iter++]);
            if (iter < ATTR_LIST.length) {
                jsonBuilder.append(',');
            }
        }
        jsonBuilder.append('}');
        assert iter == ATTR_LIST.length;

        return new Gson().fromJson(jsonBuilder.toString(), Attributes.class);
    }

    /**
     * This array lists the attributes in the same order as the values retrieved from the FC8 layer.
     */
    String[] ATTR_LIST = new String[]{
            "action_pulling",
            "lower_green",
            "gender_female",
            "upper_cotton",
            "accessory_other",
            "occlusion_accessory",
            "upper_other_color",
            "shoes_casual",
            "shoes_white",
            "lower_pants",
            "shoes_boot",
            "age_60",
            "weight_little_thin",
            "head_shoulder_mask",
            "upper_vest",
            "lower_white",
            "upper_black",
            "upper_white",
            "upper_shirt",
            "upper_silvery",
            "role_client",
            "upper_brown",
            "action_nipthing",
            "shoes_silver",
            "accessory_waistbag",
            "accessory_handbag",
            "action_picking",
            "shoes_black",
            "occlusion_down",
            "shoes_yellow",
            "gender_other",
            "accessory_shoulderbag",
            "upper_cotta",
            "occlusion_right",
            "action_pushing",
            "shoes_green",
            "action_armstretching",
            "shoes_other",
            "shoes_red",
            "lower_mix_color",
            "occlusion_left",
            "view_angle_left",
            "shoes_sport",
            "lower_gray",
            "upper_other",
            "accessory_kid",
            "head_shoulder_sunglasses",
            "lower_silver",
            "accessory_cart",
            "age_16",
            "hair_style_null",
            "upper_hoodie",
            "shoes_mix_color",
            "upper_green",
            "accessory_backpack",
            "age_older_60",
            "shoes_cloth",
            "action_chatting",
            "shoes_purple",
            "upper_suit",
            "lower_black",
            "lower_tight_pants",
            "occlusion_up",
            "action_holdthing",
            "lower_pink",
            "action_other",
            "lower_jean",
            "hair_style_long",
            "upper_red",
            "role_uniform",
            "lower_short_pants",
            "lower_one_piece",
            "lower_blue",
            "upper_tshirt",
            "upper_purple",
            "upper_pink",
            "action_lying",
            "shoes_pink",
            "shoes_shandle",
            "shoes_leather",
            "occlusion_environment",
            "view_angle_right",
            "shoes_other_color",
            "head_shoulder_with_hat",
            "age_30",
            "shoes_gray",
            "accessory_paperbag",
            "shoes_brown",
            "action_crouching",
            "lower_purple",
            "weight_very_thin",
            "shoes_blue",
            "action_gathering",
            "weight_normal",
            "action_running",
            "view_angle_front",
            "accessory_plasticbag",
            "head_shoulder_black_hair",
            "accessory_box",
            "lower_long_skirt",
            "shoes_orange",
            "weight_little_fat",
            "head_shoulder_scarf",
            "lower_other_color",
            "upper_jacket",
            "upper_gray",
            "lower_short_skirt",
            "age_45",
            "lower_skirt",
            "upper_sweater",
            "lower_brown",
            "lower_yellow",
            "occlusion_object",
            "upper_orange",
            "gender_male",
            "view_angle_back",
            "upper_blue",
            "lower_red",
            "head_shoulder_glasses",
            "upper_mix_color",
            "lower_orange",
            "upper_yellow",
            "weight_very_fat",
            "action_calling",
            "occlusion_other"};
}
