#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import  absolute_import
import os

os.environ["KMP_DUPLICATE_LIB_OK"] = "TRUE"

import numpy as np
import threading
import queue
import os, cv2
import torch
import subprocess
import sys
import time

from detectron2.utils.logger import setup_logger
setup_logger()
from detectron2 import model_zoo
from detectron2.engine import DefaultPredictor
from detectron2.config import get_cfg
from detectron2.data import MetadataCatalog
from detectron2.utils.video_visualizer import VideoVisualizer

import tkinter as tk
import matplotlib.pyplot as plt
from matplotlib.backends.backend_tkagg import FigureCanvasTkAgg, NavigationToolbar2Tk
import matplotlib.patches as patches

### Environment variables

## Lists

LEFT_TREE_LIST = []
RIGHT_TREE_LIST = []
ORIGINAL_TREE_POSITIONS = []
TRANSFORMED_TREE_POSITIONS = [] 
TREE_POSITIONS = []

## Counters

NEXT_ID = 0
COUNT = 0

## Flags

CLEAR_DIR_AT_START = False

## Hyperparameters

CONVERT_METRIC_CONST = 10
SCALE_3D_POINT_CONST = 100
SCALE_3D_POINT_CONST_X = 80

## Paths

OUTPUT_DIR = "./output"
MODEL_PATH = 'ResNext-101_fold_01.pth'
IMAGE_DIR = './projectData/MH01/mav0/cam0/data'
TIMESTAMP_FILE = './projectData/westbound_20240319.txt'
CAMERA_TRAJECTORY_WESTBOUND_PATH = './projectData/CameraTrajectory_westbound.txt'
VIDEO_WESTBOUND_PATH = './output/video.mp4'

## Tracking

PIXEL_THRESHOLD = 50

## Misc

COLORS = [
        (255, 0, 0),    # Red
        (0, 255, 0),    # Green
        (0, 0, 255),    # Blue
        (255, 255, 0),  # Yellow
        (0, 255, 255),  # Cyan
        (255, 0, 255),  # Magenta
        (192, 192, 192),# Silver
        (128, 128, 128),# Gray
        (128, 0, 0),    # Maroon
        (128, 128, 0),  # Olive
        (0, 128, 0),    # Dark Green
        (128, 0, 128),  # Purple
        (0, 128, 128),  # Teal
        (0, 0, 128),    # Navy
        (255, 165, 0),  # Orange
        (255, 192, 203) # Pink
    ]

RESIZE_FACTOR = 0.5

## Camera Settings

HEIGHT = 1.3
FOCAL_LENGTH = 16
IMAGE_WIDTH = 2704
SENSOR_WIDTH = 6.17

FX = 1418.051
FY = 1431.343
CX = 1333.374
CY = 789.099

## Visualization Flags

ORIGINAL_MODE = False # Original output of model, including masks, keypoints and confidence scores
MEASUREMENT_MODE = False # Output of model with tree width, height and depth measurements
TRACKING_MODE = False # Output of model with tree tracking and ID's
ULTIMATE_SHOWCASE_MODE = True  # Output of model with tree width, height, depth measurements, tree tracking and ID's

## Depth Configuration

DEPTH_RES_X = 1024
DEPTH_RES_Y = 320
TOO_CLOSE = 1.25

################################################################

### Helper functions

def configure_detectron():
    cfg = get_cfg()
    cfg.INPUT.MASK_FORMAT = "bitmask"
    cfg.merge_from_file(model_zoo.get_config_file("COCO-Keypoints/keypoint_rcnn_X_101_32x8d_FPN_3x.yaml"))
    cfg.DATASETS.TRAIN = ()
    cfg.DATASETS.TEST = ()
    cfg.DATALOADER.NUM_WORKERS = 8
    cfg.SOLVER.IMS_PER_BATCH = 8
    cfg.MODEL.ROI_HEADS.BATCH_SIZE_PER_IMAGE = 256
    cfg.MODEL.ROI_HEADS.NUM_CLASSES = 1
    cfg.MODEL.SEM_SEG_HEAD.NUM_CLASSES = 1
    cfg.MODEL.ROI_KEYPOINT_HEAD.NUM_KEYPOINTS = 5
    cfg.MODEL.MASK_ON = True
    cfg.OUTPUT_DIR = OUTPUT_DIR
    cfg.MODEL.WEIGHTS = os.path.join(cfg.OUTPUT_DIR, MODEL_PATH)
    cfg.MODEL.ROI_HEADS.SCORE_THRESH_TEST = 0.5
    return cfg

def pixels_to_meters(pixels, focal_length_pixels, depth):
    return (pixels * depth / focal_length_pixels) * CONVERT_METRIC_CONST

def meter_to_pixels(meters, focal_length_pixels, depth):
    return (meters * focal_length_pixels / depth) / CONVERT_METRIC_CONST

def calculate_focal_length_pixels(f_mm, sensor_width_mm, image_width_pixels):
    return (f_mm * (image_width_pixels / sensor_width_mm))

def get_3d_point(x, y, depth, fx, fy, cx, cy):
    X = (x - cx) * depth / fx
    Y = (y - cy) * depth / fy
    Z = depth
    return X/SCALE_3D_POINT_CONST, Y/SCALE_3D_POINT_CONST, Z/SCALE_3D_POINT_CONST

def initialize_trees(first_centers, split_point):
    global NEXT_ID
    global LEFT_TREE_LIST
    global RIGHT_TREE_LIST
    for center in first_centers:
        if(center[0] < split_point):
            LEFT_TREE_LIST.append({'id': NEXT_ID, 'x': center[0], 'y': center[1]})
        if(center[0] > split_point):
            RIGHT_TREE_LIST.append({'id': NEXT_ID, 'x': center[0], 'y': center[1]})
        NEXT_ID += 1

def update_left_trees(new_frame_centers):
    global NEXT_ID
    global LEFT_TREE_LIST
    updated_tree_list = []
    matched_ids = set()

    # Sort trees by x-coordinate
    new_frame_centers.sort(key=lambda center: center[0])
    LEFT_TREE_LIST.sort(key=lambda tree: tree['x'])

    # Match previous trees to new trees
    for tree in LEFT_TREE_LIST:
        tree_x = tree['x']
        closest_center = None
        min_distance = float('inf')

        for center in new_frame_centers:
            center_x = center[0]
            if center_x < tree_x and tree_x - center_x < min_distance:
                min_distance = tree_x - center_x
                closest_center = center

        if closest_center is not None:
            updated_tree_list.append({'id': tree['id'], 'x': closest_center[0], 'y': closest_center[1]})
            matched_ids.add(closest_center[0][0])
            
    # Add new trees that weren't matched
    for center in new_frame_centers:
        if center[0][0] not in matched_ids:
            updated_tree_list.append({'id': NEXT_ID, 'x': center[0], 'y': center[1]})
            NEXT_ID += 1

    # Update the tree list for the next frame
    LEFT_TREE_LIST = updated_tree_list
    
def update_right_trees(new_frame_centers):
    global NEXT_ID
    global RIGHT_TREE_LIST
    updated_tree_list = []
    matched_ids = set()

    # Sort trees by x-coordinate
    new_frame_centers.sort(key=lambda center: center[0])
    RIGHT_TREE_LIST.sort(key=lambda tree: tree['x'])

    # Match previous trees to new trees
    for tree in RIGHT_TREE_LIST:
        tree_x = tree['x']
        closest_center = None
        min_distance = float('inf')

        for center in new_frame_centers:
            center_x = center[0]
            if center_x > tree_x and center_x - tree_x < min_distance:
                min_distance = center_x - tree_x
                closest_center = center

        if closest_center is not None:
            updated_tree_list.append({'id': tree['id'], 'x': closest_center[0], 'y': closest_center[1]})
            matched_ids.add(closest_center[0][0])
            
    # Add new trees that weren't matched
    for center in new_frame_centers:
        if center[0][0] not in matched_ids:
            updated_tree_list.append({'id': NEXT_ID, 'x': center[0], 'y': center[1]})
            NEXT_ID += 1

    # Update the tree list for the next frame
    RIGHT_TREE_LIST = updated_tree_list

def get_color_for_id(tree_id):
    np.random.seed(tree_id)
    color = tuple(np.random.randint(128, 256, 3).tolist())
    return color

def quaternion_multiply(q1, q2):
    w1, x1, y1, z1 = q1
    w2, x2, y2, z2 = q2
    w = w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2
    x = w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2
    y = w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2
    z = w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2
    return w, x, y, z

def apply_multiple_rotations(position, rotation_matrix, camera_position, times=1):
    transformed_position = position
    for _ in range(times):
        transformed_position = apply_transformation(transformed_position, rotation_matrix, camera_position)
    return transformed_position

def quaternion_conjugate(q):
    w, x, y, z = q
    return (w, -x, -y, -z)

def rotate_point_by_quaternion(point, q):
    p = (0,) + point
    q_conj = quaternion_conjugate(q)
    p_rotated = quaternion_multiply(quaternion_multiply(q, p), q_conj)
    return p_rotated[1:]

def quaternion_to_rotation_matrix(q):
    q = np.array(q, dtype=np.float64)
    qx, qy, qz, qw = q
    R = np.array([
        [1 - 2*qy**2 - 2*qz**2, 2*qx*qy - 2*qz*qw, 2*qx*qz + 2*qy*qw],
        [2*qx*qy + 2*qz*qw, 1 - 2*qx**2 - 2*qz**2, 2*qy*qz - 2*qx*qw],
        [2*qx*qz - 2*qy*qw, 2*qy*qz + 2*qx*qw, 1 - 2*qx**2 - 2*qy**2]
    ])
    
    return R

def quaternion_to_y_rotation_matrix(q):
    q /= np.linalg.norm(q)
    qw, qx, qy, qz = q
    
    # Set x and z components to 0 for pure y-axis rotation
    qx, qz = 0, 0
    # Recompute w to ensure the quaternion is still valid (w^2 + y^2 = 1)
    qw = np.sqrt(max(0, 1 - qy**2))

    R = np.array([
        [qw**2 + qx**2 - qy**2 - qz**2, 2*qx*qy - 2*qz*qw, 2*qx*qz + 2*qy*qw],
        [2*qx*qy + 2*qz*qw, qw**2 - qx**2 + qy**2 - qz**2, 2*qy*qz - 2*qx*qw],
        [2*qx*qz - 2*qy*qw, 2*qy*qz + 2*qx*qw, qw**2 - qx**2 - qy**2 + qz**2]
    ])
    
    return R

def apply_transformation(position, rotation_matrix, camera_position):
    position = np.array(position)
    
    transformed_point = np.dot(rotation_matrix, position) + camera_position
    
    return transformed_point

def scale_quaternion_rotation(q, scale_factor):
    q /= np.linalg.norm(q)
    w, x, y, z = q
    angle = 2 * np.arccos(w)
    new_angle = angle * scale_factor
    s = np.sin(new_angle / 2)
    new_q = np.array([np.cos(new_angle / 2), x * s, y * s, z * s])
    
    return new_q

def create_gui(x_positions, z_positions, tree_positions, data_queue):
    root = tk.Tk()

    fig, ax1 = plt.subplots(figsize=(15, 7))
    canvas = FigureCanvasTkAgg(fig, master=root)
    canvas.get_tk_widget().pack(side=tk.TOP, fill=tk.BOTH, expand=1)

    # Add navigation toolbar for zooming and panning
    toolbar = NavigationToolbar2Tk(canvas, root)
    toolbar.update()
    canvas.get_tk_widget().pack(side=tk.TOP, fill=tk.BOTH, expand=1)

    # Set initial plot limits and aspect ratio for both axes
    view_range = 0.20  # Define how much area around the camera to display
    ax1.set_xlim(0, 1)
    ax1.set_ylim(0, 1)
    ax1.set_aspect('equal', adjustable='datalim')

    last_known_positions = {}

    def quaternion_to_yaw(q):
        # Extract components from quaternion
        x, y, z, w = q

        # Compute yaw to align with the Z-direction movement
        # Assuming the following quaternion to Euler formula for yaw (rotation around y-axis)
        siny_cosp = 2 * (w * z + x * y)
        cosy_cosp = 1 - 2 * (y * y + z * z)
        yaw = np.arctan2(siny_cosp, cosy_cosp)

        # Convert radians to degrees and adjust the angle if necessary
        yaw = np.degrees(yaw)
        
        print("angle", yaw+90)
        return yaw*(-5) + 90  # Adjusting by 90 degrees to align along the Z-axis
    
    def update_plot():
        while not data_queue.empty():
            frame_data = data_queue.get()
            frame, quaternions = frame_data
            # Check if there is sufficient data to update plot
            if len(x_positions) > frame and len(z_positions) > frame:
                ax1.clear()
                
                # Plot camera positions
                ax1.scatter(x_positions[:frame], z_positions[:frame], c='blue', marker='o', s=50)

                # Follow the most recent position
                if frame > 0:
                    current_x = x_positions[frame-1]
                    current_z = z_positions[frame-1]
                    ax1.set_xlim(current_x - view_range, current_x + view_range)
                    ax1.set_ylim(current_z - view_range, current_z + view_range)

                    yaw = quaternion_to_yaw(quaternions)
                    wedge = patches.Wedge((current_x, current_z), 0.2, yaw - 30, yaw + 30, color='blue', alpha=0.1)
                    ax1.add_patch(wedge)

                # Plot trees
                for x, z, diameter, tree_id in tree_positions:
                    if diameter and tree_id:
                        last_known_positions[tree_id] = (x, z, diameter)

                for tree_id, (x, z, diameter) in last_known_positions.items():
                    circle = plt.Circle((x, z), diameter/100, color='green', fill=False)
                    ax1.add_patch(circle)
                    ax1.text(x, z + diameter/200 + 0.01, f'ID: {tree_id}', fontsize=9, ha='center', va='bottom')

                ax1.set_xlabel('X')
                ax1.set_ylabel('Z')
                ax1.grid(False)

                canvas.draw()
            else:
                print("Insufficient data to update plot.")
        
        root.after(100, update_plot)

    root.after(100, update_plot)
    root.mainloop()
    
def process_trajectory(filepath):
    x_positions = []
    y_positions = []
    z_positions = []
    timestamps = []
    quaternions = []
    
    # Offsets to handle the restarts
    last_x = 0
    last_y = 0
    last_z = 0
    offset_x = 0
    offset_y = 0
    offset_z = 0
    segment_started = False
    
    with open(filepath, 'r') as file:
        for line in file.readlines():
            parts = line.strip().split()
            if not parts:  # Check for empty lines indicating a restart
                segment_started = False
                continue
            
            timestamp = int(float(parts[0]))
            timestamps.append(timestamp)
            
            x = float(parts[1])
            y = float(parts[2])
            z = float(parts[3])
            
            qx = float(parts[4])
            qy = float(parts[5])
            qz = float(parts[6])
            qw = float(parts[7])
            
            
            if not segment_started:
                if x_positions:
                    offset_x = x_positions[-1] - x
                    offset_y = y_positions[-1] - y
                    offset_z = z_positions[-1] - z
                segment_started = True
            
            x += offset_x
            y += offset_y
            z += offset_z
            
            x_positions.append(x)
            y_positions.append(y)
            z_positions.append(z)
            
            quaternions.append([qx, qy, qz, qw])
            
            last_x = x
            last_y = y
            last_z = z
            
    return x_positions, y_positions, z_positions, timestamps, quaternions

def extract_video_information(video_path: str):
    vcap = cv2.VideoCapture(video_path)
    w = int(vcap.get(cv2.CAP_PROP_FRAME_WIDTH))
    h = int(vcap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    fps = int(vcap.get(cv2.CAP_PROP_FPS))
    n_frames = int(vcap.get(cv2.CAP_PROP_FRAME_COUNT))
    
    success = False
    if vcap.isOpened() == True:
        success = True
        
    return w, h, fps, n_frames, success, vcap

def read_image(path):
    image = cv2.imread(path)
    success = True
    if image is None:
        success = False
    return image, success            

def crop_frame(image):
    y = 000
    x = 000
    return image[y:y+h, x:x+w]

def depth_callback(image_path):
    command = [
        sys.executable, "../monodepth2/test_simple.py",
        "--image_path", image_path,
        "--model_name", "mono+stereo_1024x320",
        "--pred_metric_depth"
    ]        
    
    result = subprocess.run(command, text=True, capture_output=True)
    
    if result.returncode == 0:
        print("Command executed successfully!")
        print("Output:", result.stdout)
    else:
        print("Error in command execution")
        print("Error:", result.stderr)

    os.system(f"python ../monodepth2/test_simple.py --image_path {image_path} --model_name mono+stereo_1024x320 --pred_metric_depth")

def transformation_callback(foot_x, foot_y, depth_at_treebase, camera_position, rotation_matrix, diameter = None, id = None):
    world_x, world_y, world_z = get_3d_point(foot_x, foot_y, depth_at_treebase, FX, FY, CX, CY)
    print("world_x", world_x, "world_y", world_y, "world_z", world_z)
    print("cam_x", camera_pos[0], "cam_y", camera_pos[1], "cam_z", camera_pos[2])

    original_pos = [world_x, world_y, world_z]
    transformed_pos = apply_transformation(original_pos, rotation_matrix, camera_position)

    ORIGINAL_TREE_POSITIONS.append(original_pos)
    TRANSFORMED_TREE_POSITIONS.append(transformed_pos)
    TREE_POSITIONS.append((transformed_pos[0], transformed_pos[2], diameter, id))

def original_callback(vid_vis, image, output_pred):
    out = vid_vis.draw_instance_predictions(image, outputs_pred["instances"].to("cpu")) 
    return out.get_image()    

def measurement_callback(img, timestamp, camera_position, rotation_matrix):
    image_path = os.path.join(OUTPUT_DIR, f"image_{timestamp}.png")
    depth_path = image_path.replace(".png", "_depth.npy")
    if not os.path.exists(depth_path):
        cv2.imwrite(image_path, img)
        depth_callback(image_path)
        # Wait for the depth file to be created
        start_time = time.time()
        timeout = 1
        while not os.path.exists(depth_path):
            time.sleep(1)
            if time.time() - start_time > timeout:
                print("Timeout waiting for the depth file to be created.")
    
    depth = np.load(depth_path)

    # Get tree keypoints and masks
    keypoints = outputs_pred["instances"].pred_keypoints.tolist()
    masks = outputs_pred["instances"].pred_masks
    for i, _ in enumerate(keypoints):
        # Get tree base and 1.3m height
        corresponding_mask = masks[i].cpu().numpy()
        y_indices, x_indices = np.where(corresponding_mask == True)
        foot_y = np.max(y_indices)
        foot_x = np.median(x_indices[y_indices == foot_y]).astype(int)
        depth_y = int(foot_y * DEPTH_RES_Y / img.shape[0])
        depth_x = int(foot_x * DEPTH_RES_X / img.shape[1])
        depth_at_treebase = depth[0, 0, depth_y, depth_x]
        height_in_pixels = int(meter_to_pixels(HEIGHT, focal_pixels, depth_at_treebase))
        
        # Get tree mask at 1.3m height
        height_for_width_measurement = foot_y - height_in_pixels
        x_indices = x_indices[y_indices == height_for_width_measurement]
    
        if len(x_indices) != 0 and depth_at_treebase > TOO_CLOSE:
            # Measure tree width from left to right border
            left_border_x = np.min(x_indices)
            right_border_x = np.max(x_indices)
            middle_x = (left_border_x + right_border_x) // 2
                        
            # Draw line for tree width
            cv2.line(img, (left_border_x, height_for_width_measurement), (right_border_x, height_for_width_measurement), color=COLORS[2], thickness=2)
            # Draw text for tree width
            line_length = right_border_x - left_border_x
            line_length_meters = pixels_to_meters(line_length, focal_pixels, depth_at_treebase)
            line_length_meters = int(line_length_meters * 100) / 100
            text_position = (left_border_x + line_length // 2, height_for_width_measurement - 10)
            cv2.putText(img, f"{line_length_meters}m", text_position, cv2.FONT_HERSHEY_SIMPLEX, 0.5, COLORS[2], 2)
            
            # Draw line for tree height
            cv2.line(img, (middle_x, foot_y), (middle_x, foot_y - height_in_pixels), color=COLORS[2], thickness=2)
            # Draw text for tree height
            height_position = (middle_x + 10, foot_y - height_in_pixels // 2)
            cv2.putText(img, f"{HEIGHT}m", height_position, cv2.FONT_HERSHEY_SIMPLEX, 0.5, COLORS[2], 2)

            # Draw depth at tree base
            depth_position = (foot_x, foot_y)  
            depth_at_treebase = int(depth_at_treebase * 100) / 100
            cv2.putText(img, f"Depth: {depth_at_treebase}m", depth_position, cv2.FONT_HERSHEY_SIMPLEX, 0.5, COLORS[2], 2)
            
            transformation_callback(foot_x, foot_y, depth_at_treebase, camera_position, rotation_matrix)

    return img

def tracking_callback(img, outputs_pred):
    # Get centroids of trees
    # 0 = kpCP, 1 = kpL, 2 = kpR, 3 = ax1, 4 = ax2
    centroids = outputs_pred["instances"].pred_keypoints[:, 0,:].tolist()

    # Merge centroids that are too close on x-axis (same tree)
    for centroid in centroids:
        for other_centroid in centroids:
            if centroid != other_centroid:
                if abs(centroid[0] - other_centroid[0]) < PIXEL_THRESHOLD:
                    centroid[0] = (centroid[0] + other_centroid[0]) / 2
                    centroid[1] = (centroid[1] + other_centroid[1]) / 2
                    centroids.remove(other_centroid)

    # Draw centroids
    centers = []
    for centroid in centroids:
        centers.append(np.array([[centroid[0]],[centroid[1]]]))
        cv2.circle(img, (int(centroid[0]), int(centroid[1])), 5, COLORS[2], -1)

    # Go over centers and update trees
    if(len(centers) > 0):
        split_point = img.shape[1] // 2

        # Tracking algorithm
        if(COUNT == 0):
            initialize_trees(centers, split_point)
        else:
            left_centers = [center for center in centers if center[0] < split_point]
            right_centers = [center for center in centers if center[0] > split_point]
            update_left_trees(left_centers)
            update_right_trees(right_centers)

        # Visualize trees with ID's
        trees = LEFT_TREE_LIST + RIGHT_TREE_LIST
        for tree in trees:
            x = int(tree['x'][0])
            y = int(tree['y'][0])
            cv2.putText(img, f"Tree {tree['id']}", (x, y), cv2.FONT_HERSHEY_SIMPLEX, 1, COLORS[2], 2)
            
    return img

def ultimate_showcase_callback(img, timestamp, camera_position, rotation_matrix):
    image_path = os.path.join(OUTPUT_DIR, f"image_{timestamp}.png")
    depth_path = image_path.replace(".png", "_depth.npy")
    if not os.path.exists(depth_path):
        cv2.imwrite(image_path, img)
        depth_callback(image_path)
        # Wait for the depth file to be created
        start_time = time.time()
        timeout = 1
        while not os.path.exists(depth_path):
            time.sleep(1)
            if time.time() - start_time > timeout:
                print("Timeout waiting for the depth file to be created.")
    
    depth = np.load(depth_path)

    # Get tree keypoints and masks
    keypoints = outputs_pred["instances"].pred_keypoints.tolist()
    masks = outputs_pred["instances"].pred_masks
    centroids = outputs_pred["instances"].pred_keypoints[:, 0,:].tolist()

    # Merge centroids that are too close on x-axis (same tree)
    for centroid in centroids:
        for other_centroid in centroids:
            if centroid != other_centroid:
                if abs(centroid[0] - other_centroid[0]) < PIXEL_THRESHOLD:
                    centroid[0] = (centroid[0] + other_centroid[0]) / 2
                    centroid[1] = (centroid[1] + other_centroid[1]) / 2
                    centroids.remove(other_centroid)

    # Better format
    centers = []
    for centroid in centroids:
        centers.append(np.array([[centroid[0]], [centroid[1]]]))

    # Initialize/update tree tracking
    if len(centers) > 0:
        split_point = img.shape[1] // 2

        if COUNT == 0:
            initialize_trees(centers, split_point)
        else:
            left_centers = [center for center in centers if center[0] < split_point]
            right_centers = [center for center in centers if center[0] > split_point]
            update_left_trees(left_centers)
            update_right_trees(right_centers)

    # Visualize trees with ID's
    trees = LEFT_TREE_LIST + RIGHT_TREE_LIST
    for tree in trees:
        x = int(tree['x'][0])
        y = int(tree['y'][0])
        tree_id = tree['id']
        color = get_color_for_id(tree_id)

        for i, keypoint_set in enumerate(keypoints):
            if int(keypoint_set[0][0]) == x:

                # Get tree base and 1.3m height
                corresponding_mask = masks[i].cpu().numpy()
                y_indices, x_indices = np.where(corresponding_mask == True)
                foot_y = np.max(y_indices)
                foot_x = np.median(x_indices[y_indices == foot_y]).astype(int)
                depth_y = int(foot_y * DEPTH_RES_Y / img.shape[0])
                depth_x = int(foot_x * DEPTH_RES_X / img.shape[1])
                depth_at_treebase = depth[0, 0, depth_y, depth_x]
                height_in_pixels = int(meter_to_pixels(HEIGHT, focal_pixels, depth_at_treebase))

                # Get tree mask at 1.3m height
                height_for_width_measurement = foot_y - height_in_pixels
                x_indices = x_indices[y_indices == height_for_width_measurement]
            
                if len(x_indices) != 0 and depth_at_treebase > TOO_CLOSE:
                    # Measure tree width from left to right border
                    left_border_x = np.min(x_indices)
                    right_border_x = np.max(x_indices)
                    middle_x = (left_border_x + right_border_x) // 2
                    # Draw line for tree width
                    cv2.line(img, (left_border_x, height_for_width_measurement), (right_border_x, height_for_width_measurement), color=color, thickness=4)
                    # Draw text for tree width
                    line_length = right_border_x - left_border_x
                    line_length_meters = pixels_to_meters(line_length, focal_pixels, depth_at_treebase)
                    line_length_meters = int(line_length_meters * 100) / 100
                    text_position = (left_border_x + line_length // 2, height_for_width_measurement - 10)
                    cv2.putText(img, f"{line_length_meters}m", text_position, cv2.FONT_HERSHEY_SIMPLEX, 0.75, color, 2)

                    # Draw line for tree height
                    cv2.line(img, (middle_x, foot_y), (middle_x, foot_y - height_in_pixels), color=color, thickness=4)
                    # Draw text for tree height
                    height_position = (middle_x + 10, foot_y - height_in_pixels // 2)
                    cv2.putText(img, f"{HEIGHT}m", height_position, cv2.FONT_HERSHEY_SIMPLEX, 0.75, color, 2)

                    # Draw depth at tree base
                    depth_position = (foot_x, foot_y)
                    depth_at_treebase = int(depth_at_treebase * 100) / 100
                    cv2.putText(img, f"Depth: {depth_at_treebase}m", depth_position, cv2.FONT_HERSHEY_SIMPLEX, 0.75, color, 2)
                    cv2.putText(img, f"Tree ID: {tree_id}", (foot_x, foot_y+25), cv2.FONT_HERSHEY_SIMPLEX, 0.75, color, 2)
                    
                    transformation_callback(foot_x, foot_y, depth_at_treebase, camera_position, rotation_matrix, line_length_meters, tree_id)
                    
    return img

if(CLEAR_DIR_AT_START):    
    directory = OUTPUT_DIR
    for filename in os.listdir(directory):
        if filename.startswith("image"):
            file_path = os.path.join(directory, filename)
            os.remove(file_path)
            print(f"Deleted {file_path}")

if __name__ == "__main__":
    torch.cuda.is_available()
    logger = setup_logger(name=__name__)
    cfg = configure_detectron()
    predictor_synth = DefaultPredictor(cfg)    
    tree_metadata = MetadataCatalog.get("my_tree_dataset").set(thing_classes=["Tree"], keypoint_names=["kpCP", "kpL", "kpR", "AX1", "AX2"])
    vid_vis = VideoVisualizer(metadata=tree_metadata)

    x_camera_positions, y_camera_positions, z_camera_positions, timestamps, camera_quaternions = process_trajectory(CAMERA_TRAJECTORY_WESTBOUND_PATH)
    
    data_queue = queue.Queue()
    gui_thread = threading.Thread(target=create_gui, args=(x_camera_positions, z_camera_positions, TREE_POSITIONS, data_queue))
    gui_thread.start()

    focal_pixels = calculate_focal_length_pixels(FOCAL_LENGTH, SENSOR_WIDTH, IMAGE_WIDTH)
    
    w, h, fps, n_frames, success, vcap = extract_video_information(VIDEO_WESTBOUND_PATH)
    
    if (success == False):
        print("Error opening video stream or file")
    
    for timestamp, quaternion, x, y, z in zip(timestamps, camera_quaternions, x_camera_positions, y_camera_positions, z_camera_positions):
        timestamp_str = str(timestamp).zfill(6)
        timestamp_int = int(timestamp)
        
        image, success = read_image(os.path.join(IMAGE_DIR, f"{timestamp_str}.png"))
        if not success:
            continue
        
        _crop_frame = crop_frame(image)
        
        if cv2.waitKey(1) == ord('q'):
                break
        
        outputs_pred = predictor_synth(_crop_frame)
        
        # Define translation / rotation
        rotation_matrix = quaternion_to_rotation_matrix(quaternion)
        print("rotation matrix", rotation_matrix)
        camera_pos = [x, y, z]
            
        im = _crop_frame
        image_path = os.path.join(OUTPUT_DIR, f"image_{timestamp}.png")
        cv2.imwrite(image_path, im)
        
        outputs_pred = predictor_synth(_crop_frame)
        
        if ORIGINAL_MODE:
            vid_frame = original_callback(vid_vis, im, outputs_pred)
        elif MEASUREMENT_MODE:
            vid_frame = measurement_callback(im, timestamp, camera_pos, rotation_matrix)
        elif TRACKING_MODE:
            vid_frame = tracking_callback(im, outputs_pred)
        elif ULTIMATE_SHOWCASE_MODE:
            vid_frame = ultimate_showcase_callback(im, timestamp, camera_pos, rotation_matrix)
        
        vid_frame = cv2.resize(vid_frame, (0, 0), fx=RESIZE_FACTOR, fy=RESIZE_FACTOR)
        cv2.imshow('frame', vid_frame)
        
        if ULTIMATE_SHOWCASE_MODE or MEASUREMENT_MODE:
            print("Putting quaternion in queue:", quaternion)
            data_queue.put((COUNT, quaternion))
        COUNT += 1
        
    vcap.release()
    cv2.destroyAllWindows()