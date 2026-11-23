
package api.utilites;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class JsonDataReader {

    public static Object[][] getJsonData(String filePath, String arrayKey) throws IOException {

        ObjectMapper mapper = new ObjectMapper();

        JsonNode rootNode = mapper.readTree(new File(filePath));

        JsonNode arrayNode = rootNode.get(arrayKey);

        List<Map<String, Object>> dataList = mapper.convertValue(
                arrayNode,
                new TypeReference<List<Map<String, Object>>>() {}
        );

        Object[][] data = new Object[dataList.size()][1];

        for (int i = 0; i < dataList.size(); i++) {
            data[i][0] = dataList.get(i);
        }

        return data;
    }
}
