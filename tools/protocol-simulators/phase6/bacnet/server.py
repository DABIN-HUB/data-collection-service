#!/usr/bin/env python3
"""BACpypes3 0.0.110 reference device for Phase 6 Docker acceptance."""

import asyncio
import logging

_debug = False

from bacpypes3.argparse import SimpleArgumentParser
from bacpypes3.app import Application
from bacpypes3.debugging import ModuleLogger, bacpypes_debugging
from bacpypes3.local.analog import AnalogValueObject
from bacpypes3.local.binary import BinaryValueObject

_log = ModuleLogger(globals())


@bacpypes_debugging
class Phase6Application:
    """提供四个真实 Present_Value 属性并周期性更新两个对象。"""

    def __init__(self, args: object) -> None:
        self.app = Application.from_args(args)
        self.bv1 = BinaryValueObject(
            objectIdentifier=("binaryValue", 1),
            objectName="BV1",
            presentValue="active",
            statusFlags=[0, 0, 0, 0],
            description="Phase 6 active binary value",
        )
        self.bv2 = BinaryValueObject(
            objectIdentifier=("binaryValue", 2),
            objectName="BV2",
            presentValue="inactive",
            statusFlags=[0, 0, 0, 0],
            description="Phase 6 inactive binary value",
        )
        self.av1 = AnalogValueObject(
            objectIdentifier=("analogValue", 1),
            objectName="AV1",
            presentValue=22.0,
            statusFlags=[0, 0, 0, 0],
            units="degreesCelsius",
            description="Phase 6 analog value 1",
        )
        self.av2 = AnalogValueObject(
            objectIdentifier=("analogValue", 2),
            objectName="AV2",
            presentValue=101.25,
            statusFlags=[0, 0, 0, 0],
            units="degreesCelsius",
            description="Phase 6 analog value 2",
        )
        for obj in (self.bv1, self.bv2, self.av1, self.av2):
            self.app.add_object(obj)
        _log.info("BACNET_DOCKER_SIM_READY device_instance=101 objects=BV1,BV2,AV1,AV2")
        asyncio.create_task(self._update_values())

    async def _update_values(self) -> None:
        values = (("active", 22.0), ("inactive", 22.5), ("active", 23.0), ("inactive", 23.5))
        index = 0
        while True:
            await asyncio.sleep(5.0)
            bv, av = values[index % len(values)]
            index += 1
            self.bv1.presentValue = bv
            self.av1.presentValue = av
            _log.info("BACNET_VALUE_UPDATE BV1=%s AV1=%s", bv, av)


async def main() -> None:
    logging.basicConfig(level=logging.INFO)
    parser = SimpleArgumentParser()
    args = parser.parse_args()
    Phase6Application(args)
    await asyncio.Future()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
