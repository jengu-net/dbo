package cloud.jengu.dbo.fleet;

import java.net.URI;

/**
 * One node of the deployment, as the reader knows it: a name to label answers
 * with, where it listens, and the deployment's token for the node-level
 * questions.
 *
 * <p>The name is the deployment's word for the node, never something the
 * node says about itself — a node that could name itself could name itself
 * as another, and every answer here is trusted exactly as far as the label
 * on it.
 *
 * @param name     what the deployment calls this node
 * @param base     the node's shared server, scheme, host and port
 * @param opsToken the token the node was given for its runtime questions
 */
public record Node(String name, URI base, String opsToken) {

    public Node {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a node has a name: every answer is labelled with it");
        }
        if (base == null) {
            throw new IllegalArgumentException("node '" + name + "' has no address");
        }
    }
}
