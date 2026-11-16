package com.mydog.sync;

import com.mydog.metadata.model.ForeignKeyDef;
import com.mydog.metadata.model.TableDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Creation order for tables that reference each other, including the shapes that a naive
 * topological sort refuses to order at all.
 */
class TableOrderingTest {

    @Test
    void referencedTableComesFirst() {
        TableDef orders = table("t_orders", fk("t_users"));
        TableDef users = table("t_users");

        assertThat(TableOrdering.orderByDependency(List.of(orders, users)))
                .extracting(TableDef::name).containsExactly("t_users", "t_orders");
    }

    @Test
    void chainsOrderTransitively() {
        TableDef items = table("t_items", fk("t_orders"));
        TableDef orders = table("t_orders", fk("t_users"));
        TableDef users = table("t_users");

        assertThat(TableOrdering.orderByDependency(List.of(items, orders, users)))
                .extracting(TableDef::name).containsExactly("t_users", "t_orders", "t_items");
    }

    @Test
    void selfReferenceDoesNotDeadlock() {
        TableDef tree = table("t_tree", fk("t_tree"));
        TableDef other = table("t_other");

        List<String> names = TableOrdering.orderByDependency(List.of(tree, other))
                .stream().map(TableDef::name).toList();

        assertThat(names).containsExactlyInAnyOrder("t_tree", "t_other");
    }

    @Test
    void mutualCycleStillReturnsEveryTable() {
        TableDef a = table("t_a", fk("t_b"));
        TableDef b = table("t_b", fk("t_a"));

        assertThat(TableOrdering.orderByDependency(List.of(a, b))).hasSize(2);
    }

    @Test
    void referenceToAnotherSchemaDoesNotConstrainOrder() {
        // the other schema is not part of this plan, so waiting for it would never resolve
        TableDef child = table("t_child", new ForeignKeyDef("fk_x", List.of("id"), "other_schema",
                "t_parent", List.of("id"), "NO ACTION", "NO ACTION"));
        TableDef unrelated = table("t_unrelated");

        // both are ready in the first round (ties break alphabetically) — the point is that the
        // child does not block waiting for a table that is not in this plan
        assertThat(TableOrdering.orderByDependency(List.of(child, unrelated)))
                .extracting(TableDef::name).containsExactly("t_child", "t_unrelated");
    }

    @Test
    void emptyAndSingleInputAreSafe() {
        assertThat(TableOrdering.orderByDependency(List.of())).isEmpty();
        assertThat(TableOrdering.orderByDependency(List.of(table("only"))))
                .extracting(TableDef::name).containsExactly("only");
    }

    // ------------------------------------------------------------------ helpers

    private static ForeignKeyDef fk(String referencedTable) {
        return new ForeignKeyDef("fk_" + referencedTable, List.of("ref_id"), "shop",
                referencedTable, List.of("id"), "NO ACTION", "NO ACTION");
    }

    private static TableDef table(String name, ForeignKeyDef... foreignKeys) {
        return new TableDef("shop", name, "CREATE TABLE `" + name + "` (`id` int NOT NULL)",
                List.of(), List.of(), List.of(foreignKeys), "utf8mb4", "utf8mb4_0900_ai_ci", 0);
    }
}
