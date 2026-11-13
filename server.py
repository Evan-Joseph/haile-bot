import os

import uvicorn

from haile_v3.app import app, runtime


if __name__ == "__main__":
    runtime.log("Haile Bot v3 启动")
    uvicorn.run(
        app,
        host=os.getenv("HAILE_BIND_HOST", "127.0.0.1"),
        port=int(os.getenv("HAILE_PORT", "8010")),
    )
