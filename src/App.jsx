import React from "react";
import { YamlEditor } from "./YamlEditor.jsx";

const stacks = ["Postgres > []"];

class App extends React.Component {
  render() {
    return (
      <div
        style={{
          display: "flex",
          flexDirection: "row",
          flexWrap: "none",
          justifyContent: "space-between",
        }}
      >
        <div
          style={{
            minWidth: "200",
            display: "flex",
            flexWrap: "none",
            overflow: "hidden",
          }}
        >
          hello
        </div>
        <div style={{ display: "flex", flexWrap: "none" }}>
          <YamlEditor />
        </div>
      </div>
    );
  }
}

export default App;
