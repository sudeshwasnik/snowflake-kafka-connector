from test_suit.test_utils import RetryableError, NonRetryableError
import json
import datetime

# test if the table is updated with the correct column
# add test if all the records from different topics safely land in the table
class TestSchemaEvolutionJson:
    def __init__(self, driver, nameSalt):
        self.driver = driver
        self.fileName = "travis_correct_schema_evolution_json"
        self.topics = []
        self.table = self.fileName + nameSalt
        self.recordNum = 100

        for i in range(2):
            self.topics.append(self.table + str(i))

        self.driver.snowflake_conn.cursor().execute(
            "Create or replace table {} (PERFORMANCE_STRING STRING)".format(self.table))

        self.records = []

        self.records.append({
            'PERFORMANCE_STRING': 'Excellent',
            'PERFORMANCE_CHAR': 'A',
            'RATING_INT': 100
        })

        self.records.append({
            'PERFORMANCE_STRING': 'Excellent',
            'RATING_DOUBLE': 0.99,
            'APPROVAL': True
        })

        self.gold_type = {
            'PERFORMANCE_STRING': 'VARCHAR',
            'PERFORMANCE_CHAR': 'VARCHAR',
            'RATING_INT': 'NUMBER',
            'RATING_DOUBLE': 'FLOAT',
            'APPROVAL': 'BOOLEAN',
            'RECORD_METADATA': 'VARIANT'
        }

        self.gold_columns = [columnName for columnName in self.gold_type]

    def getConfigFileName(self):
        return self.fileName + ".json"

    def send(self):
        for i, topic in enumerate(self.topics):
            key = []
            value = []
            for e in range(self.recordNum):
                key.append(json.dumps({'number': str(e)}).encode('utf-8'))
                value.append(json.dumps(self.records[i]).encode('utf-8'))
            self.driver.sendBytesData(topic, value, key)

    def verify(self, round):
        rows = self.driver.snowflake_conn.cursor().execute(
            "desc table {}".format(self.table)).fetchall()
        res_col = {}

        for index, row in enumerate(rows):
            self.gold_columns.remove(row[0])
            if not row[1].startswith(self.gold_type[row[0]]):
                raise NonRetryableError("Column {} has the wrong type. got: {}, expected: {}".format(row[0], row[1], self.gold_type[row[0]]))
            res_col[row[0]] = index

        print("Columns not in table: ", self.gold_columns)

        for columnName in self.gold_columns:
            raise NonRetryableError("Column {} was not created".format(columnName))

        res = self.driver.snowflake_conn.cursor().execute(
            "SELECT count(*) FROM {}".format(self.table)).fetchone()[0]
        if res == 0:
            raise RetryableError()
        elif res != len(self.topics) * self.recordNum:
            print("Number of record expected: {}, got: {}".format(len(self.topics) * self.recordNum, res))
            raise NonRetryableError("Number of record in table is different from number of record sent")

    def clean(self):
        self.driver.cleanTableStagePipe(self.table)
