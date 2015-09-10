package edu.berkeley.cs.titan;

import com.thinkaurelius.titan.core.*;
import com.thinkaurelius.titan.core.util.TitanId;
import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Vertex;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;

import java.net.URL;
import java.util.*;

public class Graph {
    final String name;
    final TitanGraph g;
    private TitanTransaction txn;
    private static List<EdgeLabel> intToAtype;

    public Graph(final String name) {
        this.name = name;
        URL props = getClass().getResource("/titan-cassandra.properties");
        Configuration titanConfiguration = null;
        try {
            titanConfiguration = new PropertiesConfiguration(props) {{
                setProperty("storage.cassandra.keyspace", name);
            }};
        } catch (ConfigurationException e) {
            e.printStackTrace();
        }

        g = TitanFactory.open(titanConfiguration);
        txn = g.buildTransaction().readOnly().start();

        intToAtype = new ArrayList<>();
        int atype = 0;
        for (EdgeLabel label = txn.getEdgeLabel(String.valueOf(atype)); label != null; atype++) {
            intToAtype.add(label);
        }
    }

    public void restartTransaction() {
        txn.commit();
        txn = g.buildTransaction().readOnly().start();
    }

    public List<Long> getNeighbors(long id) {
        List<Long> neighbors = new LinkedList<>();
        TitanVertex node = getNode(id);
        for (TitanEdge edge: node.getTitanEdges(Direction.OUT)) {
            neighbors.add(TitanId.fromVertexID(edge.getOtherVertex(node)));
        }
        return neighbors;
    }

    public Set<Long> getNodes(int propIdx, String search) {
        Set<Long> nodeIds = new HashSet<>();
        for (Vertex v: txn.getVertices("attr" + propIdx, search)) {
            long id = Long.parseLong(String.valueOf(v.getId()));
            nodeIds.add(TitanId.fromVertexId(id));
        }
        return nodeIds;
    }

    public Set<Long> getNodes(int propIdx1, String search1, int propIdx2, String search2) {
        Set<Long> ids1 = getNodes(propIdx1, search1);
        Set<Long> ids2 = getNodes(propIdx2, search2);
        ids1.retainAll(ids2);
        return ids1;
    }

    public List<Long> getNeighborNode(long id, int propIdx, String search) {
        List<Long> result = new LinkedList<>();
        TitanVertex node = getNode(id);
        for (TitanEdge edge: node.getTitanEdges(Direction.OUT)) {
            TitanVertex neighbor = edge.getOtherVertex(node);
            if (search.equals(neighbor.getProperty("attr" + propIdx))) {
                result.add(TitanId.fromVertexID(neighbor));
            }
        }
        return result;
    }

    public List<Long> getNeighborAtype(long id, int atypeIdx) {
        List<TimestampedId> neighbors = new ArrayList<>();
        TitanVertex node = getNode(id);
        for (TitanEdge edge: node.getTitanEdges(Direction.OUT, intToAtype.get(atypeIdx))) {
            TitanVertex neighbor = edge.getOtherVertex(node);
            neighbors.add(new TimestampedId(
                    (long) neighbor.getProperty("timestamp"), TitanId.fromVertexID(neighbor))
            );
        }
        Collections.sort(neighbors);

        List<Long> result = new LinkedList<>();
        for (TimestampedId neighbor: neighbors) {
            result.add(neighbor.id);
        }
        return result;
    }

    /**
     * TAO Queries
     */

    public List<String> objGet(long id) {
        TitanVertex node = getNode(id);
        List<String> results = new ArrayList<>();
        for (String key: node.getPropertyKeys()) {
            results.add((String) node.getProperty(key));
        }
        return results;
    }

    public List<Assoc> assocRange(long id, int atype, int offset, int length) {
        TitanVertex node = getNode(id);
        List<Assoc> assocs = new ArrayList<>();

        for (TitanEdge edge: node.getTitanEdges(Direction.OUT, intToAtype.get(atype))) {
            assocs.add(new Assoc(edge));
        }

        if (offset < 0 || offset >= assocs.size()) return Collections.emptyList();
        Collections.sort(assocs);
        return assocs.subList(offset, Math.min(assocs.size(), offset + length));
    }

    // TODO: use timestamp sort key
    public List<Assoc> assocGet(long id, int atype, Set<Long> dstIdSet, long low, long high) {
        TitanVertex node = getNode(id);
        List<Assoc> assocs = new ArrayList<>();
        Assoc assoc;
        for (TitanEdge edge: node.getTitanEdges(Direction.OUT, intToAtype.get(atype))) {
            if (dstIdSet.contains(TitanId.fromVertexID(edge.getOtherVertex(node)))) {
                assoc = new Assoc(edge);
                if (assoc.timestamp >= low && assoc.timestamp <= high) {
                    assocs.add(assoc);
                }
            }
        }
        Collections.sort(assocs);
        return assocs;
    }

    public long assocCount(long id, int atype) {
        TitanVertex node = getNode(id);
        long count = 0;
        for (TitanEdge edge: node.getTitanEdges(Direction.OUT, intToAtype.get(atype))) {
            ++count;
        }
        return count;
    }

    public List<Assoc> assocTimeRange(long id, int atype, long low, long high, int limit) {
        TitanVertex node = getNode(id);
        List<Assoc> assocs = new ArrayList<>();

        Assoc assoc;
        for (TitanEdge edge: node.getTitanEdges(Direction.OUT, intToAtype.get(atype))) {
            assoc = new Assoc(edge);
            if (assoc.timestamp >= low && assoc.timestamp <= high) {
                assocs.add(assoc);
            }
        }
        Collections.sort(assocs);
        return assocs.subList(0, Math.min(limit, assocs.size()));
    }

    public void warmup() {
        restartTransaction();
        long c = 0L;
        for (Vertex v: txn.getVertices()) {
            v.getId();
            for (String key: v.getPropertyKeys()) {
                Object prop = v.getProperty(key);
            }
            for (Edge e: v.getEdges(Direction.OUT)) {
                e.getVertex(Direction.IN);
            }
            if (++c % 10000 == 0)
                System.out.println("processed " + c + " nodes");
        }

        restartTransaction();
        c = 0L;
        for (Edge e: txn.getEdges()) {
            e.getId();
            e.getLabel();
            e.getVertex(Direction.IN); e.getVertex(Direction.OUT);
            for (String key: e.getPropertyKeys()) {
                Object prop = e.getProperty(key);
            }
            if (++c % 10000 == 0)
                System.out.println("processed " + c + " edges");
        }
        restartTransaction();
    }

    public String getName() {
        return name;
    }

    public void shutdown() {
        g.shutdown();
    }

    private TitanVertex getNode(long id) {
        return txn.getVertex(TitanId.toVertexId(id));
    }

    static class TimestampedId implements Comparable<TimestampedId> {
        long timestamp, id;
        public TimestampedId(long timestamp, long id) {
            this.timestamp = timestamp;
            this.id = id;
        }
        // Larger timestamp comes first.
        public int compareTo(TimestampedId that) {
            return Long.compare(this.timestamp, that.timestamp);
        }
    }
}
