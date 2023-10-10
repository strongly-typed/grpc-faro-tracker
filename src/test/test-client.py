import logging
from time import sleep

# Generate proto
# python3 -m venv .venv
# . .venv/bin/activate
# python3 -m pip install -r requirements.txt
# python3 -m grpc_tools.protoc --proto_path ../main/proto/me/nicholasnadeau --python_out=. --grpc_python_out=. faro_tracker.proto

import grpc
from google.protobuf import empty_pb2 as empty_pb2
import faro_tracker_pb2
import faro_tracker_pb2_grpc

def run_simple():
    with grpc.insecure_channel('localhost:30000') as trk_channel:

        trk_stub = faro_tracker_pb2_grpc.FaroTrackerServiceStub(trk_channel)

        logging.info('Initialize FARO tracker')
        trk_stub.Initialize(empty_pb2.Empty())
        trk_stub.MoveHome(empty_pb2.Empty());

        trk_stub.ChangeLedState(
            faro_tracker_pb2.LedState(led = faro_tracker_pb2.Led.BLUE, ledBlink = faro_tracker_pb2.LedBlink.NORMAL)
        )

        # X = 0 Orange
        #     1 Blue
        #     2 Blue and Orange
        # Y = 0 Off
        #     1 on
        #     2 blink slow
        #     3 blink normal
        #     4 blink fast

        response = trk_stub.MeasureLevel(faro_tracker_pb2.google_dot_protobuf_dot_empty__pb2.Empty())

        if response.is_success:
            print(f'{response.Rx:12.10f} {response.Ry:12.10f} {response.Rz:12.10f}')

        trk_stub.MoveHome(empty_pb2.Empty())

        while True:
            ret = trk_stub.IsTargetDetected(empty_pb2.Empty())
            print(ret.value)
            if ret.value:
                trk_stub.ChangeLedState(faro_tracker_pb2.LedState(led = faro_tracker_pb2.Led.ORANGE, ledBlink = faro_tracker_pb2.LedBlink.ON))
                trk_stub.ChangeLedState(faro_tracker_pb2.LedState(led = faro_tracker_pb2.Led.BLUE,   ledBlink = faro_tracker_pb2.LedBlink.OFF))
            else:
                trk_stub.ChangeLedState(faro_tracker_pb2.LedState(led = faro_tracker_pb2.Led.ORANGE, ledBlink = faro_tracker_pb2.LedBlink.OFF))
                trk_stub.ChangeLedState(faro_tracker_pb2.LedState(led = faro_tracker_pb2.Led.BLUE,   ledBlink = faro_tracker_pb2.LedBlink.ON))

            sleep(0.1)


if __name__ == '__main__':
    logging.basicConfig(level=logging.DEBUG)
    run_simple()
