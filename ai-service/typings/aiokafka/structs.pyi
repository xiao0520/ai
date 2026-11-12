from typing import NamedTuple

class TopicPartition(NamedTuple):
    topic: str
    partition: int

class ConsumerRecord:
    topic: str
    partition: int
    offset: int
    key: bytes | None
    value: bytes | None

class RecordMetadata:
    topic: str
    partition: int
    offset: int