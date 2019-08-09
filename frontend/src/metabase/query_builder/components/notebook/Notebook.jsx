import React from "react";

import { t } from "ttag";
import cx from "classnames";
import { Box } from "grid-styled";

import { Fixed } from "metabase/components/Position";
import Button from "metabase/components/Button";

import NativeQueryButton from "../view/NativeQueryButton";
import NotebookSteps from "./NotebookSteps";

export default function Notebook({ className, ...props }) {
  const {
    question,
    isRunnable,
    isResultDirty,
    runQuestionQuery,
    setQueryBuilderMode,
    queryBuilderMode,
  } = props;
  return (
    <Box className={cx(className, "relative mb4")} px={[2, 3]}>
      {NativeQueryButton.shouldRender({ question, queryBuilderMode }) && (
        <Fixed
          bottom={20}
          right={20}
          className="cursor-pointer text-brand-hover text-medium"
        >
          <NativeQueryButton size={18} question={question} />
        </Fixed>
      )}
      <NotebookSteps {...props} />
      {isRunnable && (
        <Button
          medium
          primary
          style={{ minWidth: 220 }}
          onClick={async () => {
            let cleanQuestion = question
              .query()
              .clean()
              .question();
            if (cleanQuestion.display() === "table") {
              cleanQuestion = cleanQuestion.setDisplayDefault();
            }
            await cleanQuestion.update();
            // switch mode before running otherwise URL update may cause it to switch back to notebook mode
            await setQueryBuilderMode("view");
            if (isResultDirty) {
              await runQuestionQuery();
            }
          }}
        >
          {t`Visualize`}
        </Button>
      )}
    </Box>
  );
}
