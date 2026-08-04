package dev.lufi.infrastructure.overlay;

import dev.lufi.infrastructure.identity.LuffyNodeId;
import java.util.Objects;

/** Resultado terminal local de uma busca lf_route. */
public sealed interface RouteSearchResult permits RouteSearchResult.NodeFound, RouteSearchResult.NodeNotFound, RouteSearchResult.RouteError {
    LuffyNodeId targetNodeId();

    record NodeFound(LuffyNodeId targetNodeId, LuffyNodeId rendezvousNodeId, int distance,
                     LuffyRouteMessage.TargetCapabilities targetCapabilities) implements RouteSearchResult {
        public NodeFound {
            Objects.requireNonNull(targetNodeId, "targetNodeId");
            Objects.requireNonNull(rendezvousNodeId, "rendezvousNodeId");
            Objects.requireNonNull(targetCapabilities, "targetCapabilities");
            if (distance < 0 || distance > 255) throw new IllegalArgumentException("distancia de rota invalida");
        }
    }

    record NodeNotFound(LuffyNodeId targetNodeId) implements RouteSearchResult {
        public NodeNotFound { Objects.requireNonNull(targetNodeId, "targetNodeId"); }
    }

    record RouteError(LuffyNodeId targetNodeId, LuffyRouteMessage.RouteErrorCode errorCode) implements RouteSearchResult {
        public RouteError {
            Objects.requireNonNull(targetNodeId, "targetNodeId");
            Objects.requireNonNull(errorCode, "errorCode");
        }
    }
}
