package com.p2s.agent.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GeoTest {
    private static JsonObject o(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** Expands line/points actions back into voxels to check what a shape really places. */
    private static Set<String> voxels(JsonArray actions) {
        Set<String> out = new HashSet<>();
        for (JsonElement element : actions) {
            JsonObject action = element.getAsJsonObject();
            String type = action.get("type").getAsString();
            if (type.equals("points")) {
                for (JsonElement p : action.getAsJsonArray("at")) {
                    JsonArray a = p.getAsJsonArray();
                    out.add(a.get(0).getAsInt() + "," + a.get(1).getAsInt() + "," + a.get(2).getAsInt());
                }
            } else {
                assertEquals("line", type);
                JsonArray from = action.getAsJsonArray("from");
                JsonArray to = action.getAsJsonArray("to");
                assertEquals(from.get(1), to.get(1), "compressed lines run along x only");
                assertEquals(from.get(2), to.get(2));
                for (int x = from.get(0).getAsInt(); x <= to.get(0).getAsInt(); x++) {
                    out.add(x + "," + from.get(1).getAsInt() + "," + from.get(2).getAsInt());
                }
            }
        }
        return out;
    }

    @Test
    void sphereIsSymmetricAndCompressed() {
        JsonArray actions = Geo.sphere(o("{\"center\":[0,0,0],\"radius\":4,\"block\":\"stone\"}"));
        Set<String> blocks = voxels(actions);
        for (String b : blocks) {
            String[] c = b.split(",");
            assertTrue(blocks.contains((-Integer.parseInt(c[0])) + "," + c[1] + "," + c[2]), "mirror in x of " + b);
            assertTrue(blocks.contains(c[0] + "," + (-Integer.parseInt(c[1])) + "," + c[2]), "mirror in y of " + b);
        }
        assertTrue(actions.size() < blocks.size() / 3, "runs must be merged into lines");
    }

    @Test
    void hollowDomeOnlyHasTheShellAboveTheCenter() {
        Set<String> blocks = voxels(Geo.dome(o("{\"center\":[0,10,0],\"radius\":5,\"block\":\"glass\"}")));
        assertFalse(blocks.contains("0,10,0"), "hollow");
        assertTrue(blocks.contains("0,15,0"), "top");
        for (String b : blocks) {
            assertTrue(Integer.parseInt(b.split(",")[1]) >= 10);
        }
    }

    @Test
    void transformsMoveCoordinatesAndFacing() {
        JsonArray stairs = JsonParser.parseString("[{\"type\":\"points\",\"block\":\"oak_stairs\",\"facing\":\"north\",\"at\":[[1,0,0]]}]")
                .getAsJsonArray();
        JsonObject rotated = Geo.rotateY(stairs, 1, new int[]{0, 0, 0}).get(0).getAsJsonObject();
        assertEquals("east", rotated.get("facing").getAsString());
        assertEquals("[[0,0,1]]", rotated.get("at").toString());
        JsonObject mirrored = Geo.mirror(JsonParser.parseString("[{\"type\":\"line\",\"block\":\"b\",\"facing\":\"east\",\"from\":[1,0,0],\"to\":[3,0,0]}]")
                .getAsJsonArray(), "x", 5).get(0).getAsJsonObject();
        assertEquals("west", mirrored.get("facing").getAsString());
        assertEquals("[9,0,0]", mirrored.get("from").toString());
        assertEquals("[7,0,0]", mirrored.get("to").toString());
        assertEquals("[[2,3,4]]", Geo.translate(stairs, new int[]{1, 3, 4}).get(0).getAsJsonObject().get("at").toString());
    }

    @Test
    void invalidArgumentsAndHugeShapesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Geo.sphere(o("{\"radius\":3,\"block\":\"stone\"}")));
        assertThrows(IllegalArgumentException.class, () -> Geo.sphere(o("{\"center\":[0,0,0],\"radius\":3}")));
        assertThrows(IllegalArgumentException.class, () -> Geo.sphere(o("{\"center\":[0,0,0],\"radius\":100,\"block\":\"stone\"}")));
    }

    @Test
    void fromVoxelsKeepsPerBlockGroups() {
        JsonArray actions = Geo.fromVoxels(JsonParser.parseString("[[0,0,0],[1,0,0],[2,0,0],[5,0,0,\"gold_block\"]]").getAsJsonArray(), "stone");
        assertEquals(2, actions.size());
        assertEquals("line", actions.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("gold_block", actions.get(1).getAsJsonObject().get("block").getAsString());
    }
}
