// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.view;

import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.accessibility.AccessibilityRecord;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Facilitates embedding of platform views in the accessibility tree generated by the accessibility bridge.
 *
 * Embedding is done by mirroring the accessibility tree of the platform view as a subtree of the flutter
 * accessibility tree.
 *
 * This class relies on hidden system APIs to extract the accessibility information and does not work starting
 * Android P; If the reflection accessors are not available we fail silently by embedding a null node, the app
 * continues working but the accessibility information for the platform view will not be embedded.
 *
 * We use the term `flutterId` for virtual accessibility node IDs in the FlutterView tree, and the term `originId`
 * for the virtual accessibility node IDs in the platform view's tree. Internally this class maintains a bidirectional
 * mapping between `flutterId`s and the corresponding platform view and `originId`.
 */
class AccessibilityViewEmbedder {
    private static final String TAG = "AccessibilityBridge";

    private final ReflectionAccessors reflectionAccessors;

    // The view to which the platform view is embedded, this is typically FlutterView.
    private final View rootAccessibilityView;

    // Maps a flutterId to the corresponding platform view and originId.
    private final SparseArray<ViewAndId> flutterIdToOrigin;

    // Maps a platform view and originId to a corresponding flutterID.
    private final Map<ViewAndId, Integer> originToFlutterId;

    // Maps an embedded view to it's screen bounds.
    // This is used to translate the coordinates of the accessibility node subtree to the main display's coordinate
    // system.
    private final Map<View, Rect> embeddedViewToDisplayBounds;

    private int nextFlutterId;

    AccessibilityViewEmbedder(@NonNull View rootAccessibiiltyView, int firstVirtualNodeId) {
        reflectionAccessors = new ReflectionAccessors();
        flutterIdToOrigin = new SparseArray<>();
        this.rootAccessibilityView = rootAccessibiiltyView;
        nextFlutterId = firstVirtualNodeId;
        originToFlutterId = new HashMap<>();
        embeddedViewToDisplayBounds = new HashMap<>();
    }

    /**
     * Returns the root accessibility node for an embedded platform view.
     *
     * @param flutterId the virtual accessibility ID for the node in flutter accessibility tree
     * @param displayBounds the display bounds for the node in screen coordinates
     */
    public AccessibilityNodeInfo getRootNode(@NonNull View embeddedView, int flutterId, @NonNull Rect displayBounds) {
        AccessibilityNodeInfo originNode = embeddedView.createAccessibilityNodeInfo();
        Long originPackedId = reflectionAccessors.getSourceNodeId(originNode);
        if (originPackedId == null) {
            return null;
        }
        embeddedViewToDisplayBounds.put(embeddedView, displayBounds);
        int originId = ReflectionAccessors.getVirtualNodeId(originPackedId);
        cacheVirtualIdMappings(embeddedView, originId, flutterId);
        return convertToFlutterNode(originNode, flutterId, embeddedView);
    }

    /**
     * Creates the accessibility node info for the node identified with `flutterId`.
     */
    @Nullable
    public AccessibilityNodeInfo createAccessibilityNodeInfo(int flutterId) {
        ViewAndId origin = flutterIdToOrigin.get(flutterId);
        if (origin == null) {
            return null;
        }
        if (!embeddedViewToDisplayBounds.containsKey(origin.view)) {
            // This might happen if the embedded view is sending accessibility event before the first Flutter semantics
            // tree was sent to the accessibility bridge. In this case we don't return a node as we do not know the
            // bounds yet.
            // https://github.com/flutter/flutter/issues/30068
            return null;
        }
        AccessibilityNodeProvider provider = origin.view.getAccessibilityNodeProvider();
        if (provider == null) {
            // The provider is null for views that don't have a virtual accessibility tree.
            // We currently only support embedding virtual hierarchies in the Flutter tree.
            // TODO(amirh): support embedding non virtual hierarchies.
            // https://github.com/flutter/flutter/issues/29717
            return null;
        }
        AccessibilityNodeInfo originNode =
                origin.view.getAccessibilityNodeProvider().createAccessibilityNodeInfo(origin.id);
        if (originNode == null) {
            return null;
        }
        return convertToFlutterNode(originNode, flutterId, origin.view);
    }

    /*
     * Creates an AccessibilityNodeInfo that can be attached to the Flutter accessibility tree and is equivalent to
     * originNode(which belongs to embeddedView). The virtual ID for the created node will be flutterId.
     */
    @NonNull
    private AccessibilityNodeInfo convertToFlutterNode(
            @NonNull AccessibilityNodeInfo originNode,
            int flutterId,
            @NonNull View embeddedView
    ) {
        AccessibilityNodeInfo result = AccessibilityNodeInfo.obtain(rootAccessibilityView, flutterId);
        result.setPackageName(rootAccessibilityView.getContext().getPackageName());
        result.setSource(rootAccessibilityView, flutterId);
        result.setClassName(originNode.getClassName());

        Rect displayBounds = embeddedViewToDisplayBounds.get(embeddedView);

        copyAccessibilityFields(originNode, result);
        setFlutterNodesTranslateBounds(originNode, displayBounds, result);
        addChildrenToFlutterNode(originNode, embeddedView, result);
        setFlutterNodeParent(originNode, embeddedView, result);

        return result;
    }

    private void setFlutterNodeParent(
            @NonNull AccessibilityNodeInfo originNode,
            @NonNull View embeddedView,
            @NonNull AccessibilityNodeInfo result
    ) {
        Long parentOriginPackedId = reflectionAccessors.getParentNodeId(originNode);
        if (parentOriginPackedId == null) {
            return;
        }
        int parentOriginId = ReflectionAccessors.getVirtualNodeId(parentOriginPackedId);
        Integer parentFlutterId = originToFlutterId.get(new ViewAndId(embeddedView, parentOriginId));
        if (parentFlutterId != null) {
            result.setParent(rootAccessibilityView, parentFlutterId);
        }
    }


    private void addChildrenToFlutterNode(
            @NonNull AccessibilityNodeInfo originNode,
            @NonNull View embeddedView,
            @NonNull AccessibilityNodeInfo resultNode
    ) {
        for (int i = 0; i < originNode.getChildCount(); i++) {
            Long originPackedId = reflectionAccessors.getChildId(originNode, i);
            if (originPackedId == null) {
                continue;
            }
            int originId = ReflectionAccessors.getVirtualNodeId(originPackedId);
            ViewAndId origin = new ViewAndId(embeddedView, originId);
            int childFlutterId;
            if (originToFlutterId.containsKey(origin)) {
                childFlutterId = originToFlutterId.get(origin);
            } else {
                childFlutterId = nextFlutterId++;
                cacheVirtualIdMappings(embeddedView, originId, childFlutterId);
            }
            resultNode.addChild(rootAccessibilityView, childFlutterId);
        }
    }

    // Caches a bidirectional mapping of (embeddedView, originId)<-->flutterId.
    // Where originId is a virtual node ID in the embeddedView's tree, and flutterId is the ID
    // of the corresponding node in the Flutter virtual accessibility nodes tree.
    private void cacheVirtualIdMappings(@NonNull View embeddedView, int originId, int flutterId) {
        ViewAndId origin = new ViewAndId(embeddedView, originId);
        originToFlutterId.put(origin, flutterId);
        flutterIdToOrigin.put(flutterId, origin);
    }

    private void setFlutterNodesTranslateBounds(
            @NonNull AccessibilityNodeInfo originNode,
            @NonNull Rect displayBounds,
            @NonNull AccessibilityNodeInfo resultNode
    ) {
        Rect boundsInParent = new Rect();
        originNode.getBoundsInParent(boundsInParent);
        resultNode.setBoundsInParent(boundsInParent);

        Rect boundsInScreen = new Rect();
        originNode.getBoundsInScreen(boundsInScreen);
        boundsInScreen.offset(displayBounds.left, displayBounds.top);
        resultNode.setBoundsInScreen(boundsInScreen);
    }

    private void copyAccessibilityFields(@NonNull AccessibilityNodeInfo input, @NonNull AccessibilityNodeInfo output) {
        output.setAccessibilityFocused(input.isAccessibilityFocused());
        output.setCheckable(input.isCheckable());
        output.setChecked(input.isChecked());
        output.setContentDescription(input.getContentDescription());
        output.setEnabled(input.isEnabled());
        output.setClickable(input.isClickable());
        output.setFocusable(input.isFocusable());
        output.setFocused(input.isFocused());
        output.setLongClickable(input.isLongClickable());
        output.setMovementGranularities(input.getMovementGranularities());
        output.setPassword(input.isPassword());
        output.setScrollable(input.isScrollable());
        output.setSelected(input.isSelected());
        output.setText(input.getText());
        output.setVisibleToUser(input.isVisibleToUser());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            output.setEditable(input.isEditable());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            output.setCanOpenPopup(input.canOpenPopup());
            output.setCollectionInfo(input.getCollectionInfo());
            output.setCollectionItemInfo(input.getCollectionItemInfo());
            output.setContentInvalid(input.isContentInvalid());
            output.setDismissable(input.isDismissable());
            output.setInputType(input.getInputType());
            output.setLiveRegion(input.getLiveRegion());
            output.setMultiLine(input.isMultiLine());
            output.setRangeInfo(input.getRangeInfo());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            output.setError(input.getError());
            output.setMaxTextLength(input.getMaxTextLength());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            output.setContextClickable(input.isContextClickable());
            // TODO(amirh): copy traversal before and after.
            // https://github.com/flutter/flutter/issues/29718
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            output.setDrawingOrder(input.getDrawingOrder());
            output.setImportantForAccessibility(input.isImportantForAccessibility());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            output.setAvailableExtraData(input.getAvailableExtraData());
            output.setHintText(input.getHintText());
            output.setShowingHintText(input.isShowingHintText());
        }
    }

    /**
     * Delegates an AccessibilityNodeProvider#requestSendAccessibilityEvent from the AccessibilityBridge to the embedded
     * view.
     *
     * @return True if the event was sent.
     */
    public boolean requestSendAccessibilityEvent(
            @NonNull View embeddedView,
            @NonNull View eventOrigin,
            @NonNull AccessibilityEvent event
    ) {
        AccessibilityEvent translatedEvent = AccessibilityEvent.obtain(event);
        Long originPackedId = reflectionAccessors.getRecordSourceNodeId(event);
        if (originPackedId == null) {
            return false;
        }
        int originVirtualId = ReflectionAccessors.getVirtualNodeId(originPackedId);
        Integer flutterId = originToFlutterId.get(new ViewAndId(embeddedView, originVirtualId));
        if (flutterId == null) {
            flutterId = nextFlutterId++;
            cacheVirtualIdMappings(embeddedView, originVirtualId, flutterId);
        }
        translatedEvent.setSource(rootAccessibilityView, flutterId);
        translatedEvent.setClassName(event.getClassName());
        translatedEvent.setPackageName(event.getPackageName());

        for (int i = 0; i < translatedEvent.getRecordCount(); i++) {
            AccessibilityRecord record = translatedEvent.getRecord(i);
            Long recordOriginPackedId = reflectionAccessors.getRecordSourceNodeId(record);
            if (recordOriginPackedId == null) {
                return false;
            }
            int recordOriginVirtualID = ReflectionAccessors.getVirtualNodeId(recordOriginPackedId);
            ViewAndId originViewAndId = new ViewAndId(embeddedView, recordOriginVirtualID);
            if (!originToFlutterId.containsKey(originViewAndId)) {
                return false;
            }
            int recordFlutterId = originToFlutterId.get(originViewAndId);
            record.setSource(rootAccessibilityView, recordFlutterId);
        }

        return rootAccessibilityView.getParent().requestSendAccessibilityEvent(eventOrigin, translatedEvent);
    }

    /**
     * Delegates an @{link AccessibilityNodeProvider#performAction} from the AccessibilityBridge to the embedded view's
     * accessibility node provider.
     *
     * @return True if the action was performed.
     */
    public boolean performAction(int flutterId, int accessibilityAction, @Nullable Bundle arguments) {
        ViewAndId origin  = flutterIdToOrigin.get(flutterId);
        if (origin == null) {
            return false;
        }
        View embeddedView = origin.view;
        AccessibilityNodeProvider provider = embeddedView.getAccessibilityNodeProvider();
        if (provider == null) {
            return false;
        }
        return provider.performAction(origin.id, accessibilityAction, arguments);
    }

    /**
     * Returns a flutterID for an accessibility record, or null if no mapping exists.
     *
     * @param embeddedView the embedded view that the record is associated with.
     */
    @Nullable
    public Integer getRecordFlutterId(@NonNull View embeddedView, @NonNull AccessibilityRecord record) {
        Long originPackedId = reflectionAccessors.getRecordSourceNodeId(record);
        if (originPackedId == null) {
            return null;
        }
        int originVirtualId = ReflectionAccessors.getVirtualNodeId(originPackedId);
        return originToFlutterId.get(new ViewAndId(embeddedView, originVirtualId));
    }

    /**
     * Delegates a View#onHoverEvent event from the AccessibilityBridge to an embedded view.
     *
     * The pointer coordinates are translated to the embedded view's coordinate system.
     */
    public boolean onAccessibilityHoverEvent(int rootFlutterId, @NonNull MotionEvent event) {
        ViewAndId origin = flutterIdToOrigin.get(rootFlutterId);
        if (origin == null) {
            return false;
        }
        Rect displayBounds = embeddedViewToDisplayBounds.get(origin.view);
        int pointerCount = event.getPointerCount();
        MotionEvent.PointerProperties[] pointerProperties = new MotionEvent.PointerProperties[pointerCount];
        MotionEvent.PointerCoords[] pointerCoords = new MotionEvent.PointerCoords[pointerCount];
        for(int i = 0; i < event.getPointerCount(); i++) {
            pointerProperties[i] = new MotionEvent.PointerProperties();
            event.getPointerProperties(i, pointerProperties[i]);

            MotionEvent.PointerCoords originCoords = new MotionEvent.PointerCoords();
            event.getPointerCoords(i, originCoords);

            pointerCoords[i] = new MotionEvent.PointerCoords((originCoords));
            pointerCoords[i].x -= displayBounds.left;
            pointerCoords[i].y -= displayBounds.top;

        }
        MotionEvent translatedEvent = MotionEvent.obtain(
                event.getDownTime(),
                event.getEventTime(),
                event.getAction(),
                event.getPointerCount(),
                pointerProperties,
                pointerCoords,
                event.getMetaState(),
                event.getButtonState(),
                event.getXPrecision(),
                event.getYPrecision(),
                event.getDeviceId(),
                event.getEdgeFlags(),
                event.getSource(),
                event.getFlags()
        );
        return origin.view.dispatchGenericMotionEvent(translatedEvent);
    }

    private static class ViewAndId {
        final View view;
        final int id;

        private ViewAndId(View view, int id) {
            this.view = view;
            this.id = id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ViewAndId viewAndId = (ViewAndId) o;
            return id == viewAndId.id &&
                    view.equals(viewAndId.view);
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + view.hashCode();
            result = prime * result + id;
            return result;
        }
    }

    private static class ReflectionAccessors {
        private final Method getSourceNodeId;
        private final Method getParentNodeId;
        private final Method getRecordSourceNodeId;
        private final Method getChildId;

        private ReflectionAccessors() {
            Method getSourceNodeId = null;
            Method getParentNodeId = null;
            Method getRecordSourceNodeId = null;
            Method getChildId = null;
            // Reflection access is not allowed starting Android P.
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O) {
                try {
                    getSourceNodeId = AccessibilityNodeInfo.class.getMethod("getSourceNodeId");
                } catch (NoSuchMethodException e) {
                    Log.w(TAG, "can't invoke AccessibilityNodeInfo#getSourceNodeId with reflection");
                }
                try {
                    getParentNodeId = AccessibilityNodeInfo.class.getMethod("getParentNodeId");
                } catch (NoSuchMethodException e) {
                    Log.w(TAG, "can't invoke getParentNodeId with reflection");
                }
                try {
                    getRecordSourceNodeId = AccessibilityRecord.class.getMethod("getSourceNodeId");
                } catch (NoSuchMethodException e) {
                    Log.w(TAG, "can't invoke AccessibiiltyRecord#getSourceNodeId with reflection");
                }
                try {
                    getChildId = AccessibilityNodeInfo.class.getMethod("getChildId", int.class);
                } catch (NoSuchMethodException e) {
                    Log.w(TAG, "can't invoke getChildId with reflection");
                }
            }
            this.getSourceNodeId = getSourceNodeId;
            this.getParentNodeId = getParentNodeId;
            this.getRecordSourceNodeId = getRecordSourceNodeId;
            this.getChildId = getChildId;
        }

        /** Returns virtual node ID given packed node ID used internally in accessibility API. */
        private static int getVirtualNodeId(long nodeId) {
            return (int) (nodeId >> 32);
        }

        @Nullable
        private Long getSourceNodeId(@NonNull AccessibilityNodeInfo node) {
            if (getSourceNodeId == null) {
                return null;
            }
            try {
                return (Long) getSourceNodeId.invoke(node);
            } catch (IllegalAccessException e) {
                Log.w(TAG, e);
            } catch (InvocationTargetException e) {
                Log.w(TAG, e);
            }
            return null;
        }

        @Nullable
        private Long getChildId(@NonNull AccessibilityNodeInfo node, int child) {
            if (getChildId == null) {
                return null;
            }
            try {
                return (Long) getChildId.invoke(node, child);
            } catch (IllegalAccessException e) {
                Log.w(TAG, e);
            } catch (InvocationTargetException e) {
                Log.w(TAG, e);
            }
            return null;
        }

        @Nullable
        private Long getParentNodeId(@NonNull AccessibilityNodeInfo node) {
            if (getParentNodeId == null) {
                return null;
            }
            try {
                return (long) getParentNodeId.invoke(node);
            } catch (IllegalAccessException e) {
                Log.w(TAG, e);
            } catch (InvocationTargetException e) {
                Log.w(TAG, e);
            }
            return null;
        }

        @Nullable
        private Long getRecordSourceNodeId(@NonNull AccessibilityRecord node) {
            if (getRecordSourceNodeId == null) {
                return null;
            }
            try {
                return (Long) getRecordSourceNodeId.invoke(node);
            } catch (IllegalAccessException e) {
                Log.w(TAG, e);
            } catch (InvocationTargetException e) {
                Log.w(TAG, e);
            }
            return null;
        }
    }
}
