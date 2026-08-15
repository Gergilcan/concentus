package com.concentus.mcp;

import java.util.List;

/**
 * A group of related Studio tools, contributed as a Spring bean.
 *
 * <p>The controller collects every implementation rather than naming them, so adding a subject
 * area is adding a class — and, more usefully, so the surface can be trimmed by not registering a
 * bean rather than by editing a list two files away.
 *
 * @see FlowStudioTools designing, validating and storing flows
 * @see RunStudioTools launching runs and steering them while they execute
 * @see ResourceStudioTools the library a flow's nodes point at
 */
public interface StudioToolset {

    /** This group's tools. Names must be unique across every toolset; the controller enforces it. */
    List<StudioTool> tools();
}
