package net.ekaii.redstone.region.ac;

/**
 * Minimal in-memory replacement for upstream Alternate Current's Config
 * (which persisted enabled-state and update-order to disk per world). In our
 * plugin, enabled-state is controlled per-chunk via {@code ChunkRegistry}, so
 * this carries only the update-order parameter.
 */
final class Config {
    private UpdateOrder updateOrder = UpdateOrder.HORIZONTAL_FIRST_OUTWARD;

    Config() {}

    UpdateOrder getUpdateOrder() {
        return updateOrder;
    }

    void setUpdateOrder(UpdateOrder updateOrder) {
        if (updateOrder != null) this.updateOrder = updateOrder;
    }
}
